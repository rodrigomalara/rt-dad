package com.company.rtdad.common;

import java.time.Instant;

/**
 * Shared, immutable contract published by the consumer to the {@code rtdad_anomalies_outbound}
 * fanout exchange when an anomaly is confirmed.
 */
public record AnomalyEvent(Instant timestamp, double value, double zScore) {}
