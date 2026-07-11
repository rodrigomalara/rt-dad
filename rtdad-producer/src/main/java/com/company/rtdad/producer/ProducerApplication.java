package com.company.rtdad.producer;

import com.company.rtdad.producer.cli.ProducerArgs;
import com.company.rtdad.producer.domain.MetricGenerator;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ProducerApplication {

    static void main(String[] args) {
        SpringApplication.run(ProducerApplication.class, args);
    }

    @Bean
    public ProducerArgs producerArgs(ApplicationArguments applicationArguments) {
        return ProducerArgs.from(applicationArguments);
    }

    @Bean
    public MetricGenerator metricGenerator(ProducerArgs args) {
        return new MetricGenerator(
                args.mean(), args.stddev(), args.anomalyProbability(), new java.util.Random());
    }

    /**
     * Maps CLI-provided connection args onto the auto-configured {@link CachingConnectionFactory}.
     */
    @Bean
    static BeanPostProcessor connectionFactoryCliOverride(
            ApplicationArguments applicationArguments) {
        ProducerArgs args = ProducerArgs.from(applicationArguments);
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof CachingConnectionFactory connectionFactory) {
                    connectionFactory.setHost(args.host());
                    connectionFactory.setPort(args.port());
                    connectionFactory.setUsername(args.username());
                    connectionFactory.setPassword(args.password());
                }
                return bean;
            }
        };
    }
}
