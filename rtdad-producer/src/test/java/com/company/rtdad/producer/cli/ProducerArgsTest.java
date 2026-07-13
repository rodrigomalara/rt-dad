package com.company.rtdad.producer.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

class ProducerArgsTest {

    @Test
    void defaultsWhenNoArgsGiven() {
        ApplicationArguments args = new DefaultApplicationArguments();

        ProducerArgs producerArgs = ProducerArgs.from(args);

        assertThat(producerArgs.host()).isEqualTo("localhost");
        assertThat(producerArgs.port()).isEqualTo(5672);
        assertThat(producerArgs.username()).isEqualTo("dev");
        assertThat(producerArgs.password()).isEqualTo("dev");
        assertThat(producerArgs.exchange()).isEqualTo("rtdad.metrics");
        assertThat(producerArgs.routingKey()).isEqualTo("rtdad_metrics_inbound");
        assertThat(producerArgs.rateMs()).isEqualTo(200);
        assertThat(producerArgs.mean()).isEqualTo(100.0);
        assertThat(producerArgs.stddev()).isEqualTo(15.0);
        assertThat(producerArgs.anomalyProbability()).isEqualTo(0.05);
        assertThat(producerArgs.duration()).isEqualTo(OptionalLong.empty());
    }

    @Test
    void durationSecondsPositiveIsPresent() {
        ApplicationArguments args = new DefaultApplicationArguments("--duration-seconds=30");

        ProducerArgs producerArgs = ProducerArgs.from(args);

        assertThat(producerArgs.duration()).isEqualTo(OptionalLong.of(30));
    }

    @Test
    void durationSecondsZeroIsNormalizedToEmpty() {
        ApplicationArguments args = new DefaultApplicationArguments("--duration-seconds=0");

        ProducerArgs producerArgs = ProducerArgs.from(args);

        assertThat(producerArgs.duration()).isEqualTo(OptionalLong.empty());
    }

    @Test
    void customValuesOverrideDefaults() {
        ApplicationArguments args =
                new DefaultApplicationArguments(
                        "--host=broker",
                        "--port=5673",
                        "--username=alice",
                        "--password=secret",
                        "--exchange=custom.exchange",
                        "--routing-key=custom.key",
                        "--rate-ms=500",
                        "--mean=50",
                        "--stddev=5",
                        "--anomaly-probability=0.2");

        ProducerArgs producerArgs = ProducerArgs.from(args);

        assertThat(producerArgs.host()).isEqualTo("broker");
        assertThat(producerArgs.port()).isEqualTo(5673);
        assertThat(producerArgs.username()).isEqualTo("alice");
        assertThat(producerArgs.password()).isEqualTo("secret");
        assertThat(producerArgs.exchange()).isEqualTo("custom.exchange");
        assertThat(producerArgs.routingKey()).isEqualTo("custom.key");
        assertThat(producerArgs.rateMs()).isEqualTo(500);
        assertThat(producerArgs.mean()).isEqualTo(50.0);
        assertThat(producerArgs.stddev()).isEqualTo(5.0);
        assertThat(producerArgs.anomalyProbability()).isEqualTo(0.2);
    }
}
