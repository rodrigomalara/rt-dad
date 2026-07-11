package com.company.rtdad.consumer.domain;

import com.company.rtdad.common.MetricPoint;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure Z-score anomaly detector: evaluate-then-add, anti-poisoning, and regime-shift aware. Not
 * thread-safe by design — the consumer runs single-threaded. No Spring dependency.
 */
public class AnomalyDetector {

    private static final Logger LOG = LoggerFactory.getLogger(AnomalyDetector.class);

    private final double zThreshold;
    private final double regimeShiftRunFraction;

    // Run state for regime-shift detection. A "run" is a streak of consecutive anomalies all on
    // the same side of the mean. runSign (+1/-1) is that side; runBuffer holds the run's raw values
    // so the window can be reseeded from them once the run is long enough to be the new normal.
    private int consecutiveAnomalies;
    private int runSign;
    private final Deque<Double> runBuffer = new ArrayDeque<>();

    public AnomalyDetector(double zThreshold, double regimeShiftRunFraction) {
        this.zThreshold = zThreshold;
        this.regimeShiftRunFraction = regimeShiftRunFraction;
    }

    public DetectionResult evaluate(MetricPoint point, RollingWindow window) {
        double value = point.value();

        // NaN/Inf would irreversibly corrupt the window's running sum/sum-of-squares, so drop the
        // point entirely rather than admit it. Reported OK (not an anomaly) with a null z-score.
        if (!Double.isFinite(value)) {
            LOG.warn(
                    "Dropping non-finite metric value {} at {}; window left unchanged",
                    value,
                    point.timestamp());
            return new DetectionResult(point, DetectionResult.Status.OK, null);
        }

        // During warm-up we have no stable baseline, so admit everything unconditionally and never
        // flag. z-score is null because it isn't meaningful yet.
        if (!window.isWarm()) {
            resetRun();
            window.add(value);
            return new DetectionResult(point, DetectionResult.Status.OK, null);
        }

        double mean = window.mean();
        double stddev = window.stddev();

        // stddev == 0 means a perfectly flat window; z = (value-mean)/stddev would divide by zero.
        // Treat an exact match as normal (z=0) and any deviation as a definite anomaly (z=+Inf).
        double z;
        boolean anomaly;
        if (stddev == 0.0) {
            if (value == mean) {
                z = 0.0;
                anomaly = false;
            } else {
                z = Double.POSITIVE_INFINITY;
                anomaly = true;
            }
        } else {
            z = Math.abs(value - mean) / stddev;
            anomaly = z > zThreshold;
        }

        // Anti-poisoning: only normal points are admitted to the window (evaluate-then-add). That
        // is the whole point of scoring before add() — anomalous values must not shift the
        // baseline, otherwise a slow drift of outliers would silently redefine "normal".
        if (!anomaly) {
            resetRun();
            window.add(value);
            return new DetectionResult(point, DetectionResult.Status.OK, z);
        }

        // Anomalous, but maybe a regime shift: a sustained one-sided run of anomalies looks less
        // like noise and more like the signal genuinely moving to a new level. Track the streak; a
        // change of side resets it (an up-spike then a down-spike are unrelated, not a trend).
        int sign = value >= mean ? 1 : -1;
        if (sign == runSign) {
            consecutiveAnomalies++;
        } else {
            runSign = sign;
            consecutiveAnomalies = 1;
            runBuffer.clear();
        }
        runBuffer.addLast(value);

        // Trigger threshold scales with window size but is floored at 2, so a single isolated spike
        // can never be mistaken for a regime shift, even with a tiny window or fraction.
        int k = Math.max(2, (int) Math.ceil(regimeShiftRunFraction * window.maxSamples()));

        if (consecutiveAnomalies >= k) {
            // Rebase the window onto just the run's values so the new level becomes the baseline,
            // then re-score this point against that fresh baseline for a meaningful post-shift z.
            window.reseed(runBuffer);
            resetRun();
            double newMean = window.mean();
            double newStddev = window.stddev();
            double newZ;
            if (newStddev == 0.0) {
                newZ = value == newMean ? 0.0 : Double.POSITIVE_INFINITY;
            } else {
                newZ = Math.abs(value - newMean) / newStddev;
            }
            return new DetectionResult(point, DetectionResult.Status.REGIME_SHIFT, newZ);
        }

        return new DetectionResult(point, DetectionResult.Status.ANOMALY, z);
    }

    private void resetRun() {
        consecutiveAnomalies = 0;
        runSign = 0;
        runBuffer.clear();
    }
}
