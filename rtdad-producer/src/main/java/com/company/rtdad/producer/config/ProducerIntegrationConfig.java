package com.company.rtdad.producer.config;

import com.company.rtdad.common.MetricPoint;
import com.company.rtdad.producer.cli.ProducerArgs;
import com.company.rtdad.producer.domain.MetricGenerator;
import java.time.Duration;
import java.time.Instant;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.integration.amqp.dsl.Amqp;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.core.MessageSource;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.endpoint.SourcePollingChannelAdapter;
import org.springframework.integration.support.json.JacksonJsonObjectMapper;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.scheduling.support.PeriodicTrigger;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ProducerIntegrationConfig {

    public static final String DLX_NAME = "rtdad.metrics.dlx";

    @Bean
    public Declarables producerTopology(ProducerArgs args) {
        DirectExchange exchange = new DirectExchange(args.exchange(), true, false);
        Queue queue =
                QueueBuilder.durable(args.routingKey())
                        .withArgument("x-dead-letter-exchange", DLX_NAME)
                        .build();
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(args.routingKey());
        return new Declarables(exchange, queue, binding);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setRetryTemplate(retryTemplate());
        return template;
    }

    private RetryTemplate retryTemplate() {
        RetryPolicy retryPolicy = RetryPolicy.builder().maxRetries(3).build();
        return new RetryTemplate(retryPolicy);
    }

    @Bean
    public MessageChannel producerChannel() {
        return new DirectChannel();
    }

    @Bean
    public MessageSource<MetricPoint> metricMessageSource(MetricGenerator generator) {
        return () -> new GenericMessage<>(new MetricPoint(Instant.now(), generator.next()));
    }

    @Bean
    public SourcePollingChannelAdapter producerPoller(
            MessageSource<MetricPoint> metricMessageSource,
            MessageChannel producerChannel,
            ProducerArgs args) {
        SourcePollingChannelAdapter adapter = new SourcePollingChannelAdapter();
        adapter.setSource(metricMessageSource);
        adapter.setOutputChannel(producerChannel);
        adapter.setTrigger(new PeriodicTrigger(Duration.ofMillis(args.rateMs())));
        // The source always returns a message (never null), so without this cap a single poll
        // cycle would receive in a tight loop and ignore the trigger interval. One point per tick.
        adapter.setMaxMessagesPerPoll(1);
        adapter.setAutoStartup(true);
        return adapter;
    }

    /**
     * Wrap Boot's autoconfigured Jackson 3 {@link JsonMapper} so Spring Integration's JSON
     * transformer uses Jackson 3 (Boot 4) instead of the absent Jackson 2 default.
     */
    @Bean
    public JacksonJsonObjectMapper jsonObjectMapper(JsonMapper jsonMapper) {
        return new JacksonJsonObjectMapper(jsonMapper);
    }

    @Bean
    public IntegrationFlow producerFlow(
            MessageChannel producerChannel,
            RabbitTemplate rabbitTemplate,
            ProducerArgs args,
            JacksonJsonObjectMapper jsonObjectMapper) {
        return IntegrationFlow.from(producerChannel)
                .transform(Transformers.toJson(jsonObjectMapper))
                .handle(
                        Amqp.outboundAdapter(rabbitTemplate)
                                .exchangeName(args.exchange())
                                .routingKey(args.routingKey()))
                .get();
    }
}
