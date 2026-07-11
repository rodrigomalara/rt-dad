package com.company.rtdad.common;

import java.time.Instant;

/** A single generated metric sample, published by the producer. */
public record MetricPoint(Instant timestamp, double value) {}
