package com.company.rtdad.consumer.domain;

import java.util.Collection;

/**
 * Fixed-capacity ring buffer tracking running sum and sum-of-squares for O(1) mean/variance. A dumb
 * buffer: it never decides what to admit, only stores. No Spring dependency.
 */
public class RollingWindow {

    private final double[] buffer;
    private final int maxSamples;
    private final int minSamples;
    private int size;
    private int head;
    private double sum;
    private double sumSq;

    public RollingWindow(int maxSamples, int minSamples) {
        this.maxSamples = maxSamples;
        this.minSamples = minSamples;
        this.buffer = new double[maxSamples];
    }

    public void add(double value) {
        if (size < maxSamples) {
            buffer[(head + size) % maxSamples] = value;
            size++;
            sum += value;
            sumSq += value * value;
        } else {
            double evicted = buffer[head];
            sum -= evicted;
            sumSq -= evicted * evicted;
            buffer[head] = value;
            sum += value;
            sumSq += value * value;
            head = (head + 1) % maxSamples;
        }
    }

    public void reseed(Collection<Double> values) {
        size = 0;
        head = 0;
        sum = 0;
        sumSq = 0;
        for (double v : values) {
            add(v);
        }
    }

    public int size() {
        return size;
    }

    public int maxSamples() {
        return maxSamples;
    }

    public int minSamples() {
        return minSamples;
    }

    public boolean isWarm() {
        return size >= minSamples;
    }

    public double mean() {
        return size == 0 ? 0.0 : sum / size;
    }

    public double stddev() {
        if (size < 2) {
            return 0.0;
        }
        double mean = mean();
        double variance = (sumSq - size * mean * mean) / (size - 1);
        return Math.sqrt(Math.max(0.0, variance));
    }
}
