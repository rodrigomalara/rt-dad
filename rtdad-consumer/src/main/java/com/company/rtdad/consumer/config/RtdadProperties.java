package com.company.rtdad.consumer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Setter
@Getter
@ConfigurationProperties(prefix = "rtdad")
public class RtdadProperties {

    @NestedConfigurationProperty private Window window = new Window();
    @NestedConfigurationProperty private Detector detector = new Detector();
    @NestedConfigurationProperty private Amqp amqp = new Amqp();

    @Setter
    @Getter
    public static class Window {
        private int minSamples = 50;
        private int maxSamples = 100;
    }

    @Setter
    @Getter
    public static class Detector {
        private double zThreshold = 3.0;
    }

    @Setter
    @Getter
    public static class Amqp {
        private String inboundQueue = "rtdad_metrics_inbound";
        private String dlq = "rtdad_metrics_inbound_dlq";
        private String metricsExchange = "rtdad.metrics";
        private String dlx = "rtdad.metrics.dlx";
        private String anomaliesExchange = "rtdad_anomalies_outbound";
    }
}
