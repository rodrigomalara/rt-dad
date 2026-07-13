package com.company.rtdad.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "spring.rabbitmq.listener.direct.auto-startup=false"
        })
class PrometheusEndpointTest {

    @LocalServerPort private int port;

    @Test
    void prometheusEndpointExposesCustomMetricsAtZero() {
        RestClient restClient = RestClient.create("http://localhost:" + port);
        ResponseEntity<String> response =
                restClient.get().uri("/actuator/prometheus").retrieve().toEntity(String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        String body = response.getBody();
        assertThat(body).isNotNull();
        // All eight custom families present.
        assertThat(body).contains("anomaly_detector_messages_total");
        assertThat(body).contains("anomaly_detector_processing_seconds");
        assertThat(body).contains("anomaly_detector_rolling_mean");
        assertThat(body).contains("anomaly_detector_rolling_stddev");
        assertThat(body).contains("anomaly_detector_window_size");
        assertThat(body).contains("anomaly_detector_window_warm");
        assertThat(body).contains("anomaly_detector_zscore");
        assertThat(body).contains("anomaly_detector_dead_letters_total");
        // Every status series pre-registered at zero. The management.metrics.tags.application
        // common tag prepends application="rtdad-consumer" ahead of the status tag.
        assertThat(body)
                .contains(
                        "anomaly_detector_messages_total{application=\"rtdad-consumer\",status=\"ok\"} 0.0");
        assertThat(body)
                .contains(
                        "anomaly_detector_messages_total{application=\"rtdad-consumer\",status=\"anomaly\"} 0.0");
        assertThat(body)
                .contains(
                        "anomaly_detector_messages_total{application=\"rtdad-consumer\",status=\"regime_shift\"} 0.0");
        assertThat(body)
                .contains(
                        "anomaly_detector_messages_total{application=\"rtdad-consumer\",status=\"dropped\"} 0.0");
    }
}
