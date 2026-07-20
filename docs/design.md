# RT-DAD — Design & Detection Rationale

Welcome! This document explains the math and logic behind the anomaly detector. It answers *why* the detector behaves the way it does. 

If looking for instructions on how to build, run, or operate the project, please head back to the [README](../README.md).

## How Anomalies are Injected (The Producer)

To make sure the detector actually works, a reliable way to test it is needed. This is done by having the producer intentionally inject outliers. 

Here is how it works:
Based on the `--anomaly-probability` flag, the producer will randomly decide to emit an outlier instead of a normal data point. When it does, it generates a value that is way outside the norm: `mean ± (8 to 12) * stddev`. 

Because this value is so extreme (its Z-score is much greater than 3), the consumer's warm window is practically guaranteed to flag it and trigger an `ANOMALY DETECTED!` alert. If the producer keeps generating these back-to-back, it will trigger a "regime shift" (more on that below).

## How Anomalies are Detected

When a new data point arrives at the consumer, the detector scores it **against the window's prior state**. This is known as "evaluate-then-add." 

Why score *before* adding the point? Because if the massive outlier were added to the window first, it would inflate the window's mean and standard deviation, effectively hiding its own extremity (suppressing its own Z-score).

### Anti-Poisoning

If a point is confirmed as an anomaly (a transient spike), it is logged and published to the anomalies fanout, but it is **not admitted** to the rolling window. This prevents "poisoning": a burst of random, one-off outliers cannot drag the baseline statistics off course.

### Adapting to Change: The Regime Shift

A single massive spike is just noise. But what if a *sustained, same-direction* run of anomalies is seen? That is strong evidence that the underlying system hasn't just glitched, but has genuinely shifted to a new baseline. This is called a **regime shift**.

The detector keeps track of consecutive anomalies that go in the same direction (e.g., all positive or all negative). It considers it a true regime shift when the streak reaches a specific threshold:

```text
K = max(2, ceil(regime-shift-run-fraction * max-samples))
```

Notice that this threshold is a **fraction of the window size**, not a hardcoded number. This means a 100-point window that tolerates a 10-point run is exactly as sensitive as a 50-point window that tolerates a 5-point run.

**When a regime shift happens, the following steps occur:**
1. **The window is reseeded:** That run of anomalies is taken and made the new baseline (`window.reseed(...)`).
2. **A notice is logged:** `REGIME SHIFT` is logged as an informational notice, not a critical alert. It is also **not** published to the anomalies fanout, because this represents the system successfully adapting, not a fault.
3. **The warm-up resets:** After reseeding, the window might temporarily dip below `min-samples` and re-enter its warm-up phase. This is totally expected and safe behavior.

*Note on streak-breaking:* If a streak of positive anomalies occurs and suddenly a massive negative outlier appears, the streak counter resets. That opposite outlier is treated as noise breaking up the trend, not a continuation of it.
