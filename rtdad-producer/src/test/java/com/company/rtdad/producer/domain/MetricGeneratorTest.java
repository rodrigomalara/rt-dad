package com.company.rtdad.producer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;
import org.junit.jupiter.api.Test;

class MetricGeneratorTest {

    private static final double MEAN = 100.0;
    private static final double STDDEV = 15.0;

    @Test
    void normalPointsStayWithinSixSigma() {
        MetricGenerator generator = new MetricGenerator(MEAN, STDDEV, 0.0, new Random(42L));

        for (int i = 0; i < 10_000; i++) {
            double value = generator.next();
            assertThat(value).isBetween(MEAN - 6 * STDDEV, MEAN + 6 * STDDEV);
        }
    }

    @Test
    void everySampleIsAnOutlierWhenProbabilityIsOne() {
        MetricGenerator generator = new MetricGenerator(MEAN, STDDEV, 1.0, new Random(42L));

        for (int i = 0; i < 10_000; i++) {
            double value = generator.next();
            assertThat(Math.abs(value - MEAN)).isGreaterThanOrEqualTo(8 * STDDEV);
        }
    }

    @Test
    void anomalyRateMatchesConfiguredProbabilityWithinTolerance() {
        double anomalyProbability = 0.05;
        MetricGenerator generator =
                new MetricGenerator(MEAN, STDDEV, anomalyProbability, new Random(42L));

        int samples = 10_000;
        long anomalyCount = 0;
        for (int i = 0; i < samples; i++) {
            double value = generator.next();
            if (Math.abs(value - MEAN) >= 8 * STDDEV) {
                anomalyCount++;
            }
        }

        double rate = (double) anomalyCount / samples;
        assertThat(rate).isBetween(0.03, 0.07);
    }

    @Test
    void seedConvenienceConstructorIsDeterministic() {
        MetricGenerator a = new MetricGenerator(MEAN, STDDEV, 0.05, 42L);
        MetricGenerator b = new MetricGenerator(MEAN, STDDEV, 0.05, 42L);

        for (int i = 0; i < 100; i++) {
            assertThat(a.next()).isEqualTo(b.next());
        }
    }
}
