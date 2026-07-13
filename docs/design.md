# RT-DAD — Design & detection rationale

Why the detector behaves the way it does. For build/run/operate instructions
see the [README](../README.md).

## Anomaly injection (producer)

With probability `--anomaly-probability` per point, the producer emits an
outlier instead of a normal sample: `mean ± (8..12) * stddev`, sign random.
This guarantees Z >> 3 once the consumer's window is warm, so injected
outliers reliably trigger `ANOMALY DETECTED!` (or, if sustained, a regime
shift — see below).

## Anomaly detection semantics

The detector scores each point **against the window's prior state**
(evaluate-then-add) — adding the point first would let it inflate its own
mean/stddev and suppress its own Z-score. A confirmed anomaly (a transient
spike) is logged/published but **not admitted** to the window
(anti-poisoning): a burst of one-off outliers cannot drag the reference
statistics off course.

### Regime shift

A single spike is noise; a *sustained, same-direction* run of anomalies is
evidence the underlying process has genuinely shifted to a new baseline.
The detector tracks a run of consecutive same-sign anomalies. Once the run
reaches:

```
K = max(2, ceil(regime-shift-run-fraction * max-samples))
```

(a **fraction of the window**, not an absolute count — a 100-point window
tolerating a 10-point run is proportionally as sensitive as a 50-point
window tolerating 5), the detector treats it as a regime shift:

- The window is **reseeded** with the buffered run (`window.reseed(...)`) —
  the run becomes the new baseline.
- This is logged as `REGIME SHIFT` (a notice, not an alert) and is **not**
  published to the anomalies fanout — it marks adaptation, not a fault.
- An opposite-sign outlier mid-run resets the counter (it's noise breaking
  up the trend, not a continuation of it).
- After reseeding, the window may briefly dip below `min-samples` and
  re-enter warm-up — this is expected, safe behavior.
