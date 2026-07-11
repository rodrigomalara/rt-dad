package com.company.rtdad.consumer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.company.rtdad.common.MetricPoint;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class AnomalyDetectorTest {

    private static MetricPoint point(double value) {
        return new MetricPoint(Instant.now(), value);
    }

    private static RollingWindow warmWindowOf(double value, int minSamples, int maxSamples) {
        RollingWindow window = new RollingWindow(maxSamples, minSamples);
        for (int i = 0; i < minSamples; i++) {
            window.add(value);
        }
        return window;
    }

    @Test
    void aboveThresholdIsAnomaly() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = new RollingWindow(100, 50);
        for (int i = 0; i < 50; i++) {
            window.add(100);
        }
        // manufacture stddev=5 by adding varied values around 100 without breaking warm state
        window = new RollingWindow(100, 50);
        double[] seed = new double[50];
        for (int i = 0; i < 50; i++) {
            seed[i] = 100 + (i % 2 == 0 ? 5 : -5);
        }
        for (double v : seed) {
            window.add(v);
        }
        double mean = window.mean();
        double stddev = window.stddev();

        DetectionResult anomalyResult = detector.evaluate(point(mean + 6 * stddev), window);
        assertThat(anomalyResult.status()).isEqualTo(DetectionResult.Status.ANOMALY);
        assertThat(anomalyResult.zScore()).isCloseTo(6.0, within(1e-6));
    }

    @Test
    void belowThresholdIsOk() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = new RollingWindow(100, 50);
        double[] seed = new double[50];
        for (int i = 0; i < 50; i++) {
            seed[i] = 100 + (i % 2 == 0 ? 5 : -5);
        }
        for (double v : seed) {
            window.add(v);
        }
        double mean = window.mean();
        double stddev = window.stddev();

        DetectionResult okResult = detector.evaluate(point(mean + 2 * stddev), window);
        assertThat(okResult.status()).isEqualTo(DetectionResult.Status.OK);
        assertThat(okResult.zScore()).isCloseTo(2.0, within(1e-6));
    }

    @Test
    void notWarmIsOkWithNullZScoreAndPointStillAdmitted() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = new RollingWindow(100, 50);

        DetectionResult result = detector.evaluate(point(999999), window);

        assertThat(result.status()).isEqualTo(DetectionResult.Status.OK);
        assertThat(result.zScore()).isNull();
        assertThat(window.size()).isEqualTo(1);
    }

    @Test
    void flatWindowEqualValueIsOkWithZeroZScore() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = warmWindowOf(100, 50, 100);

        DetectionResult result = detector.evaluate(point(100), window);

        assertThat(result.status()).isEqualTo(DetectionResult.Status.OK);
        assertThat(result.zScore()).isEqualTo(0.0);
    }

    @Test
    void flatWindowDifferentValueIsAnomalyWithInfiniteZScore() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = warmWindowOf(100, 50, 100);

        DetectionResult result = detector.evaluate(point(500), window);

        assertThat(result.status()).isEqualTo(DetectionResult.Status.ANOMALY);
        assertThat(result.zScore()).isEqualTo(Double.POSITIVE_INFINITY);
    }

    @Test
    void antiPoisoningKeepsWindowUnchangedAfterConfirmedAnomaly() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = warmWindowOf(100, 50, 100);
        double meanBefore = window.mean();
        double stddevBefore = window.stddev();

        DetectionResult first = detector.evaluate(point(500), window);
        assertThat(first.status()).isEqualTo(DetectionResult.Status.ANOMALY);
        assertThat(window.mean()).isEqualTo(meanBefore);
        assertThat(window.stddev()).isEqualTo(stddevBefore);

        DetectionResult second = detector.evaluate(point(500), window);
        assertThat(second.status()).isEqualTo(DetectionResult.Status.ANOMALY);
        assertThat(window.mean()).isEqualTo(meanBefore);
        assertThat(window.stddev()).isEqualTo(stddevBefore);
    }

    @Test
    void regimeShiftAfterKConsecutiveSameDirectionAnomalies() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = warmWindowOf(100, 50, 100);
        // K = max(2, ceil(0.10 * 100)) = 10

        for (int i = 0; i < 9; i++) {
            DetectionResult result = detector.evaluate(point(500), window);
            assertThat(result.status()).isEqualTo(DetectionResult.Status.ANOMALY);
            assertThat(window.mean()).isEqualTo(100.0);
        }

        DetectionResult tenth = detector.evaluate(point(500), window);
        assertThat(tenth.status()).isEqualTo(DetectionResult.Status.REGIME_SHIFT);
        // reseed uses the ten buffered 500s: new window is perfectly flat, so z is exactly 0.0
        assertThat(tenth.zScore()).isEqualTo(0.0);

        DetectionResult after = detector.evaluate(point(500), window);
        assertThat(after.status()).isEqualTo(DetectionResult.Status.OK);
    }

    @Test
    void exactlyOnThresholdIsOkUnderStrictGreaterThan() {
        RollingWindow window = new RollingWindow(100, 50);
        double[] seed = new double[50];
        for (int i = 0; i < 50; i++) {
            seed[i] = 100 + (i % 2 == 0 ? 5 : -5);
        }
        for (double v : seed) {
            window.add(v);
        }
        double mean = window.mean();
        double stddev = window.stddev();

        // Pick the point first, derive the exact z the detector will compute, then set the
        // threshold equal to it. anomaly = z > zThreshold must be false (strict greater-than).
        MetricPoint borderline = point(mean + 3 * stddev);
        double exactZ = Math.abs(borderline.value() - mean) / stddev;
        AnomalyDetector detector = new AnomalyDetector(exactZ, 0.10);

        DetectionResult result = detector.evaluate(borderline, window);
        assertThat(result.status()).isEqualTo(DetectionResult.Status.OK);
        assertThat(result.zScore()).isEqualTo(exactZ);
    }

    @Test
    void nonFiniteValueIsDroppedAndWindowUnchanged() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = warmWindowOf(100, 50, 100);
        double meanBefore = window.mean();
        double stddevBefore = window.stddev();
        int sizeBefore = window.size();

        for (double bad :
                new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            DetectionResult result = detector.evaluate(point(bad), window);
            assertThat(result.status()).isEqualTo(DetectionResult.Status.OK);
            assertThat(result.zScore()).isNull();
            assertThat(window.size()).isEqualTo(sizeBefore);
            assertThat(window.mean()).isEqualTo(meanBefore);
            assertThat(window.stddev()).isEqualTo(stddevBefore);
        }
    }

    @Test
    void oppositeSignOutlierMidRunResetsCounter() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = warmWindowOf(100, 50, 100);

        for (int i = 0; i < 5; i++) {
            detector.evaluate(point(500), window);
        }
        // opposite sign resets the run
        DetectionResult reset = detector.evaluate(point(-500), window);
        assertThat(reset.status()).isEqualTo(DetectionResult.Status.ANOMALY);

        // now needs a fresh run of K same-direction (negative) anomalies; 8 more (total 9) still
        // ANOMALY
        for (int i = 0; i < 8; i++) {
            DetectionResult result = detector.evaluate(point(-500), window);
            assertThat(result.status()).isEqualTo(DetectionResult.Status.ANOMALY);
        }
        DetectionResult tenth = detector.evaluate(point(-500), window);
        assertThat(tenth.status()).isEqualTo(DetectionResult.Status.REGIME_SHIFT);
    }

    @Test
    void kDerivesFromCeilOfFractionTimesMaxSamples() {
        AnomalyDetector detector = new AnomalyDetector(3.0, 0.10);
        RollingWindow window = warmWindowOf(100, 25, 50);
        // K = max(2, ceil(0.10 * 50)) = 5

        for (int i = 0; i < 4; i++) {
            DetectionResult result = detector.evaluate(point(500), window);
            assertThat(result.status()).isEqualTo(DetectionResult.Status.ANOMALY);
        }
        DetectionResult fifth = detector.evaluate(point(500), window);
        assertThat(fifth.status()).isEqualTo(DetectionResult.Status.REGIME_SHIFT);
    }
}
