package com.company.rtdad.producer.cli;

import java.util.List;
import java.util.OptionalLong;
import org.springframework.boot.ApplicationArguments;

/** Immutable, parsed CLI options for the producer. */
public record ProducerArgs(
        String host,
        int port,
        String username,
        String password,
        String exchange,
        String routingKey,
        OptionalLong duration,
        long rateMs,
        double mean,
        double stddev,
        double anomalyProbability) {

    public static ProducerArgs from(ApplicationArguments args) {
        return new ProducerArgs(
                stringOption(args, "host", "localhost"),
                intOption(args, "port", 5672),
                stringOption(args, "username", "dev"),
                stringOption(args, "password", "dev"),
                stringOption(args, "exchange", "rtdad.metrics"),
                stringOption(args, "routing-key", "rtdad_metrics_inbound"),
                durationOption(args),
                longOption(args, "rate-ms", 200),
                doubleOption(args, "mean", 100.0),
                doubleOption(args, "stddev", 15.0),
                doubleOption(args, "anomaly-probability", 0.05));
    }

    private static OptionalLong durationOption(ApplicationArguments args) {
        List<String> values = args.getOptionValues("duration-seconds");
        if (values == null || values.isEmpty()) {
            return OptionalLong.empty();
        }
        long value = Long.parseLong(values.getFirst());
        return value > 0 ? OptionalLong.of(value) : OptionalLong.empty();
    }

    private static String stringOption(ApplicationArguments args, String name, String fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }

    private static int intOption(ApplicationArguments args, String name, int fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : Integer.parseInt(values.getFirst());
    }

    private static long longOption(ApplicationArguments args, String name, long fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? fallback : Long.parseLong(values.getFirst());
    }

    private static double doubleOption(ApplicationArguments args, String name, double fallback) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty()
                ? fallback
                : Double.parseDouble(values.getFirst());
    }
}
