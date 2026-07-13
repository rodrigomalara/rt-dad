package com.company.rtdad.consumer.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.rtdad.common.MetricPoint;
import com.company.rtdad.consumer.domain.DetectionResult;
import com.company.rtdad.consumer.domain.RollingWindow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DetectorMetricsTest {

    private static MetricPoint point(double value) {
        return new MetricPoint(Instant.now(), value);
    }

    private static DetectorMetrics metricsWith(RollingWindow window) {
        return new DetectorMetrics(new SimpleMeterRegistry(), window);
    }

    private double counter(DetectorMetrics m, String status) {
        return m.registry()
                .get("anomaly.detector.messages")
                .tag("status", status)
                .counter()
                .count();
    }

    private double gauge(DetectorMetrics m, String name) {
        return m.registry().get(name).gauge().value();
    }

    @Test
    void allStatusCountersPreRegisteredAtZero() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        assertThat(counter(m, "ok")).isZero();
        assertThat(counter(m, "anomaly")).isZero();
        assertThat(counter(m, "regime_shift")).isZero();
        assertThat(counter(m, "dropped")).isZero();
    }

    @Test
    void okResultIncrementsOkCounter() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        m.record(new DetectionResult(point(5.0), DetectionResult.Status.OK, 1.2));
        assertThat(counter(m, "ok")).isEqualTo(1.0);
    }

    @Test
    void anomalyResultIncrementsAnomalyCounter() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        m.record(new DetectionResult(point(9.0), DetectionResult.Status.ANOMALY, 4.5));
        assertThat(counter(m, "anomaly")).isEqualTo(1.0);
    }

    @Test
    void regimeShiftResultIncrementsRegimeShiftCounter() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        m.record(new DetectionResult(point(9.0), DetectionResult.Status.REGIME_SHIFT, 4.5));
        assertThat(counter(m, "regime_shift")).isEqualTo(1.0);
    }

    @Test
    void nonFiniteValueClassifiedAsDroppedRegardlessOfStatus() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        m.record(new DetectionResult(point(Double.NaN), DetectionResult.Status.OK, null));
        assertThat(counter(m, "dropped")).isEqualTo(1.0);
        assertThat(counter(m, "ok")).isZero();
    }

    @Test
    void summaryRecordsOnlyFiniteZScores() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        m.record(
                new DetectionResult(
                        point(5.0), DetectionResult.Status.OK, 1.5)); // finite -> recorded
        m.record(
                new DetectionResult(
                        point(5.0), DetectionResult.Status.OK, null)); // null -> skipped
        m.record(
                new DetectionResult(
                        point(5.0),
                        DetectionResult.Status.ANOMALY,
                        Double.POSITIVE_INFINITY)); // Inf -> skipped
        assertThat(m.registry().get("anomaly.detector.zscore").summary().count()).isEqualTo(1L);
    }

    @Test
    void gaugesReflectLiveWindowState() {
        RollingWindow window = new RollingWindow(100, 50);
        DetectorMetrics m = metricsWith(window);
        for (int i = 0; i < 50; i++) {
            window.add(i % 2 == 0 ? 105.0 : 95.0); // mean 100, non-zero stddev
        }
        assertThat(gauge(m, "anomaly.detector.window.size")).isEqualTo(50.0);
        assertThat(gauge(m, "anomaly.detector.rolling.mean")).isEqualTo(100.0);
        assertThat(gauge(m, "anomaly.detector.rolling.stddev")).isGreaterThan(0.0);
    }

    @Test
    void windowWarmGaugeIsZeroWhileWarmingAndOneWhenWarm() {
        RollingWindow window = new RollingWindow(100, 50);
        DetectorMetrics m = metricsWith(window);
        assertThat(gauge(m, "anomaly.detector.window.warm")).isEqualTo(0.0);
        for (int i = 0; i < 50; i++) {
            window.add(100.0);
        }
        assertThat(gauge(m, "anomaly.detector.window.warm")).isEqualTo(1.0);
    }

    @Test
    void windowWarmGaugeIsZeroAfterReseedBelowMinSamples() {
        RollingWindow window = new RollingWindow(100, 50);
        DetectorMetrics m = metricsWith(window);
        for (int i = 0; i < 50; i++) {
            window.add(100.0);
        }
        assertThat(gauge(m, "anomaly.detector.window.warm")).isEqualTo(1.0);
        window.reseed(new double[] {200.0, 201.0, 199.0}, 3); // 3 points < min-samples 50
        assertThat(gauge(m, "anomaly.detector.window.warm")).isEqualTo(0.0);
        assertThat(gauge(m, "anomaly.detector.window.size")).isEqualTo(3.0);
    }

    @Test
    void timeEvaluationRecordsTimerAndReturnsResult() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        DetectionResult expected = new DetectionResult(point(5.0), DetectionResult.Status.OK, 1.0);
        DetectionResult returned = m.timeEvaluation(() -> expected);
        assertThat(returned).isSameAs(expected);
        assertThat(m.registry().get("anomaly.detector.processing.seconds").timer().count())
                .isEqualTo(1L);
    }

    @Test
    void recordDeadLetterIncrementsCounter() {
        DetectorMetrics m = metricsWith(new RollingWindow(100, 50));
        m.recordDeadLetter();
        m.recordDeadLetter();
        assertThat(m.registry().get("anomaly.detector.dead.letters").counter().count())
                .isEqualTo(2.0);
    }
}
