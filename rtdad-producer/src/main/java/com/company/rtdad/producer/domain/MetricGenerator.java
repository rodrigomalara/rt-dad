package com.company.rtdad.producer.domain;

import java.util.Random;

/**
 * Pure, seedable generator producing normally-distributed metric values with occasional injected
 * outliers. No Spring dependency.
 */
public class MetricGenerator {

    private final double mean;
    private final double stddev;
    private final double anomalyProbability;
    private final Random rng;

    public MetricGenerator(double mean, double stddev, double anomalyProbability, Random rng) {
        this.mean = mean;
        this.stddev = stddev;
        this.anomalyProbability = anomalyProbability;
        this.rng = rng;
    }

    public MetricGenerator(double mean, double stddev, double anomalyProbability, long seed) {
        this(mean, stddev, anomalyProbability, new Random(seed));
    }

    public double next() {
        if (rng.nextDouble() < anomalyProbability) {
            double sign = rng.nextBoolean() ? 1.0 : -1.0;
            double magnitude = (8 + rng.nextDouble() * 4) * stddev;
            return mean + sign * magnitude;
        }
        return mean + rng.nextGaussian() * stddev;
    }
}
