package com.company.rtdad.consumer.observability;

import com.company.rtdad.consumer.domain.DetectionResult;
import com.company.rtdad.consumer.domain.RollingWindow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Observability tap for the anomaly-detection flow. The only class in the module that imports
 * Micrometer; the pure domain classes ({@code AnomalyDetector}, {@code RollingWindow}, {@code
 * DetectionResult}) stay Micrometer-free. Meters are updated from Spring Integration {@code
 * .handle()} steps wired in {@code ConsumerIntegrationConfig}.
 */
@Component
public class DetectorMetrics {

    private static final String MESSAGES = "anomaly.detector.messages";
    private static final String ZSCORE = "anomaly.detector.zscore";

    private final MeterRegistry registry;
    private final Map<Status, Counter> statusCounters = new EnumMap<>(Status.class);
    private final DistributionSummary zScoreSummary;
    private final Timer processingTimer;
    private final Counter deadLetterCounter;

    /** Metric-facing status, distinct from {@link DetectionResult.Status} (adds DROPPED). */
    private enum Status {
        OK("ok"),
        ANOMALY("anomaly"),
        REGIME_SHIFT("regime_shift"),
        DROPPED("dropped");

        private final String tag;

        Status(String tag) {
            this.tag = tag;
        }
    }

    public DetectorMetrics(MeterRegistry registry, RollingWindow window) {
        this.registry = registry;
        // Pre-register every status series so they exist at zero before the first message.
        for (Status s : Status.values()) {
            statusCounters.put(
                    s, Counter.builder(MESSAGES).tag("status", s.tag).register(registry));
        }
        this.zScoreSummary = DistributionSummary.builder(ZSCORE).register(registry);

        // Gauges bind directly to the injected window and are sampled at scrape time; benign
        // cross-thread reads (scrape thread vs. the single consumer writer).
        Gauge.builder("anomaly.detector.window.size", window, RollingWindow::size)
                .register(registry);
        Gauge.builder("anomaly.detector.rolling.mean", window, RollingWindow::mean)
                .register(registry);
        Gauge.builder("anomaly.detector.rolling.stddev", window, RollingWindow::stddev)
                .register(registry);
        Gauge.builder("anomaly.detector.window.warm", window, w -> w.isWarm() ? 1.0 : 0.0)
                .register(registry);

        this.processingTimer =
                Timer.builder("anomaly.detector.processing.seconds").register(registry);
        this.deadLetterCounter =
                Counter.builder("anomaly.detector.dead.letters").register(registry);
    }

    /** Increment the status counter and record a finite z-score into the summary. */
    public void record(DetectionResult result) {
        statusCounters.get(classify(result)).increment();

        Double z = result.zScore();
        // Skip null (warm-up/dropped) and +/-Inf (stddev==0 branch): both would corrupt the
        // summary.
        if (z != null && Double.isFinite(z)) {
            zScoreSummary.record(z);
        }
    }

    /** Time the core evaluation and return its result. Wraps {@code AnomalyDetector.evaluate}. */
    public DetectionResult timeEvaluation(Supplier<DetectionResult> evaluation) {
        return processingTimer.record(evaluation);
    }

    /** Increment the dead-letter counter. Called from the dead-letter flow. */
    public void recordDeadLetter() {
        deadLetterCounter.increment();
    }

    private Status classify(DetectionResult result) {
        // A non-finite raw value is a dropped data-quality event, which the detector reports as OK.
        if (!Double.isFinite(result.point().value())) {
            return Status.DROPPED;
        }
        return switch (result.status()) {
            case OK -> Status.OK;
            case ANOMALY -> Status.ANOMALY;
            case REGIME_SHIFT -> Status.REGIME_SHIFT;
        };
    }

    /** Package-visible for tests. */
    MeterRegistry registry() {
        return registry;
    }
}
