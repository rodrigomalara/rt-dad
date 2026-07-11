package com.company.rtdad.consumer.domain;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Pure formatter (and SLF4J delegator) owning the strict log format. */
public class DetectionLogger {

    private static final Logger LOG = LoggerFactory.getLogger(DetectionLogger.class);

    public String format(DetectionResult result) {
        String timestamp = result.point().timestamp().toString();
        String value = String.format(Locale.ROOT, "%.2f", result.point().value());
        String zScore = formatZScore(result.zScore());

        return switch (result.status()) {
            case OK ->
                    String.format(
                            "[%s] Data point: %s | Status: OK | Z-score: %s",
                            timestamp, value, zScore);
            case ANOMALY ->
                    String.format(
                            "[%s] Data point: %s | Status: ANOMALY DETECTED! | Z-score: %s | ALERT: Significant"
                                    + " deviation detected.",
                            timestamp, value, zScore);
            case REGIME_SHIFT ->
                    String.format(
                            "[%s] Data point: %s | Status: REGIME SHIFT | Z-score: %s | NOTICE: Sustained level"
                                    + " change; window reseeded to new baseline.",
                            timestamp, value, zScore);
        };
    }

    private String formatZScore(Double zScore) {
        if (zScore == null) {
            return "N/A";
        }
        if (Double.isInfinite(zScore)) {
            return "Inf";
        }
        return String.format(Locale.ROOT, "%.2f", zScore);
    }

    public void log(DetectionResult result) {
        LOG.info(format(result));
    }
}
