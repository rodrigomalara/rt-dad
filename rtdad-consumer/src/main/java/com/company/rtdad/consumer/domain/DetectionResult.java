package com.company.rtdad.consumer.domain;

import com.company.rtdad.common.MetricPoint;

/** Outcome of evaluating a {@link MetricPoint} against the rolling window. */
public record DetectionResult(MetricPoint point, Status status, Double zScore) {

    public enum Status {
        OK,
        ANOMALY,
        REGIME_SHIFT
    }
}
