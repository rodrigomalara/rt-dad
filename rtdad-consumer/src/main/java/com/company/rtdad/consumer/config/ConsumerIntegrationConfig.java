package com.company.rtdad.consumer.config;

import com.company.rtdad.common.AnomalyEvent;
import com.company.rtdad.common.MetricPoint;
import com.company.rtdad.consumer.domain.AnomalyDetector;
import com.company.rtdad.consumer.domain.DetectionLogger;
import com.company.rtdad.consumer.domain.DetectionResult;
import com.company.rtdad.consumer.domain.RollingWindow;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.json.JsonToObjectTransformer;
import org.springframework.integration.support.json.JacksonJsonObjectMapper;
import org.springframework.messaging.MessageHeaders;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ConsumerIntegrationConfig {

    private static final Logger DLQ_LOG = LoggerFactory.getLogger("com.company.rtdad.consumer.dlq");

    @Bean
    public Declarables consumerTopology(RtdadProperties properties) {
        RtdadProperties.Amqp amqp = properties.getAmqp();

        DirectExchange metricsExchange = new DirectExchange(amqp.getMetricsExchange(), true, false);
        DirectExchange dlx = new DirectExchange(amqp.getDlx(), true, false);
        Queue inboundQueue =
                QueueBuilder.durable(amqp.getInboundQueue())
                        .withArgument("x-dead-letter-exchange", amqp.getDlx())
                        .build();
        Queue dlq = QueueBuilder.durable(amqp.getDlq()).build();
        Binding inboundBinding =
                BindingBuilder.bind(inboundQueue).to(metricsExchange).with(amqp.getInboundQueue());
        Binding dlqBinding = BindingBuilder.bind(dlq).to(dlx).with(amqp.getInboundQueue());
        FanoutExchange anomaliesExchange =
                new FanoutExchange(amqp.getAnomaliesExchange(), true, false);

        return new Declarables(
                metricsExchange,
                dlx,
                inboundQueue,
                dlq,
                inboundBinding,
                dlqBinding,
                anomaliesExchange);
    }

    @Bean
    public RollingWindow rollingWindow(RtdadProperties properties) {
        return new RollingWindow(
                properties.getWindow().getMaxSamples(), properties.getWindow().getMinSamples());
    }

    @Bean
    public AnomalyDetector anomalyDetector(RtdadProperties properties) {
        return new AnomalyDetector(
                properties.getDetector().getZThreshold(),
                properties.getDetector().getRegimeShiftRunFraction());
    }

    @Bean
    public DetectionLogger detectionLogger() {
        return new DetectionLogger();
    }

    /**
     * Spring Integration's JSON transformers default to the Jackson 2 mapper, which is absent under
     * Spring Boot 4 (Jackson 3). Wrap Boot's autoconfigured Jackson 3 {@link JsonMapper} so the
     * {@code application.yml} Jackson settings (ISO-8601 timestamps) are honored.
     */
    @Bean
    public JacksonJsonObjectMapper jsonObjectMapper(JsonMapper jsonMapper) {
        return new JacksonJsonObjectMapper(jsonMapper);
    }

    @Bean
    public IntegrationFlow anomalyDetectionFlow(
            ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            RtdadProperties properties,
            RollingWindow window,
            AnomalyDetector detector,
            DetectionLogger logger,
            JacksonJsonObjectMapper jsonObjectMapper) {
        // Concurrency is pinned at 1 by design, NOT a throughput default to be tuned up.
        // AnomalyDetector and RollingWindow are single-threaded and mutate shared window state per
        // message; correct detection depends on strict in-order, serial processing. Raising this,
        // or adding virtual threads, would corrupt the window — parallelism would need a per-series
        // window first.
        return IntegrationFlow.from(
                        Amqp.inboundAdapter(
                                        connectionFactory, properties.getAmqp().getInboundQueue())
                                .configureContainer(
                                        c ->
                                                c.concurrentConsumers(1)
                                                        .maxConcurrentConsumers(1)
                                                        .defaultRequeueRejected(false)))
                .transform(new JsonToObjectTransformer(MetricPoint.class, jsonObjectMapper))
                .handle(
                        MetricPoint.class,
                        (point, _) -> detector.evaluate(point, window))
                .handle(
                        DetectionResult.class,
                        (result, _) -> {
                            logger.log(result);
                            return result;
                        })
                .filter(
                        DetectionResult.class,
                        result -> result.status() == DetectionResult.Status.ANOMALY)
                .transform(
                        DetectionResult.class,
                        result ->
                                new AnomalyEvent(
                                        result.point().timestamp(),
                                        result.point().value(),
                                        result.zScore()))
                .transform(Transformers.toJson(jsonObjectMapper))
                .handle(
                        Amqp.outboundAdapter(rabbitTemplate)
                                .exchangeName(properties.getAmqp().getAnomaliesExchange()))
                .get();
    }

    @Bean
    public IntegrationFlow deadLetterFlow(
            ConnectionFactory connectionFactory, RtdadProperties properties) {
        return IntegrationFlow.from(
                        Amqp.inboundAdapter(connectionFactory, properties.getAmqp().getDlq())
                                .messageConverter(new SimpleMessageConverter())
                                .configureContainer(c -> c.concurrentConsumers(1))
                                .id("deadLetterInboundAdapter"))
                .handle(
                        byte[].class,
                        (payload, headers) -> {
                            logDeadLetter(payload, headers);
                            return null;
                        })
                .get();
    }

    private void logDeadLetter(byte[] payload, MessageHeaders headers) {
        DLQ_LOG.warn(
                "Dead-lettered message: {} | x-death={}",
                new String(payload, StandardCharsets.UTF_8),
                headers.get("x-death"));
    }
}
