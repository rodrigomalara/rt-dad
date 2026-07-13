package com.company.rtdad.consumer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.rtdad.common.MetricPoint;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DetectionLoggerTest {

    private static final Instant TIMESTAMP = Instant.parse("2023-10-27T14:32:01.123Z");
    private final DetectionLogger logger = new DetectionLogger();

    @Test
    void formatsOkWarmResult() {
        DetectionResult result =
                new DetectionResult(
                        new MetricPoint(TIMESTAMP, 105.42), DetectionResult.Status.OK, 1.2);

        assertThat(logger.format(result))
                .isEqualTo(
                        "[2023-10-27T14:32:01.123Z] Data point: 105.42 | Status: OK | Z-score: 1.20");
    }

    @Test
    void formatsWarmupResultWithNullZScore() {
        DetectionResult result =
                new DetectionResult(
                        new MetricPoint(TIMESTAMP, 105.42), DetectionResult.Status.OK, null);

        assertThat(logger.format(result))
                .isEqualTo(
                        "[2023-10-27T14:32:01.123Z] Data point: 105.42 | Status: OK | Z-score: N/A");
    }

    @Test
    void formatsAnomalyResult() {
        DetectionResult result =
                new DetectionResult(
                        new MetricPoint(TIMESTAMP, 105.42), DetectionResult.Status.ANOMALY, 6.0);

        assertThat(logger.format(result))
                .isEqualTo(
                        "[2023-10-27T14:32:01.123Z] Data point: 105.42 | Status: ANOMALY DETECTED! |"
                                + " Z-score: 6.00 | ALERT: Significant deviation detected.");
    }

    @Test
    void formatsFlatWindowAnomalyWithInfiniteZScore() {
        DetectionResult result =
                new DetectionResult(
                        new MetricPoint(TIMESTAMP, 105.42),
                        DetectionResult.Status.ANOMALY,
                        Double.POSITIVE_INFINITY);

        assertThat(logger.format(result))
                .isEqualTo(
                        "[2023-10-27T14:32:01.123Z] Data point: 105.42 | Status: ANOMALY DETECTED! |"
                                + " Z-score: Inf | ALERT: Significant deviation detected.");
    }

    @Test
    void formatsRegimeShiftResult() {
        DetectionResult result =
                new DetectionResult(
                        new MetricPoint(TIMESTAMP, 105.42),
                        DetectionResult.Status.REGIME_SHIFT,
                        0.31);

        assertThat(logger.format(result))
                .isEqualTo(
                        "[2023-10-27T14:32:01.123Z] Data point: 105.42 | Status: REGIME SHIFT |"
                                + " Z-score: 0.31 | NOTICE: Sustained level change; window reseeded to new"
                                + " baseline.");
    }
}
