package com.company.rtdad.consumer;

import com.company.rtdad.consumer.config.RtdadProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(RtdadProperties.class)
public class ConsumerApplication {

    static void main(String[] args) {
        SpringApplication.run(ConsumerApplication.class, args);
    }
}
