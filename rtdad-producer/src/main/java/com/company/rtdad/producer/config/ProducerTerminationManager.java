package com.company.rtdad.producer.config;

import com.company.rtdad.producer.cli.ProducerArgs;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.integration.endpoint.SourcePollingChannelAdapter;
import org.springframework.stereotype.Component;

/**
 * Orchestrates ordered, graceful shutdown of the producer: stop the poller before the AMQP
 * connection is torn down, so no in-flight point is dropped.
 *
 * <p>When {@code --duration-seconds} is a positive value, schedules the shutdown after that
 * duration. Otherwise, the producer runs until Ctrl+C; the {@link #stopPollerBeforeShutdown()} hook
 * still guarantees the poller stops before the connection closes.
 */
@Component
public class ProducerTerminationManager {

    private static final Logger LOG = LoggerFactory.getLogger(ProducerTerminationManager.class);
    private static final long DRAIN_MILLIS = 500;

    private final SourcePollingChannelAdapter producerPoller;
    private final ProducerArgs args;
    private final ConfigurableApplicationContext context;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public ProducerTerminationManager(
            SourcePollingChannelAdapter producerPoller,
            ProducerArgs args,
            ConfigurableApplicationContext context) {
        this.producerPoller = producerPoller;
        this.args = args;
        this.context = context;
    }

    @PostConstruct
    void scheduleShutdownIfBounded() {
        args.duration()
                .ifPresent(
                        seconds -> {
                            LOG.info(
                                    "Producer will run for {}s then perform an ordered shutdown.",
                                    seconds);
                            scheduler.schedule(this::gracefulShutdown, seconds, TimeUnit.SECONDS);
                        });
    }

    @PreDestroy
    void stopPollerBeforeShutdown() {
        if (producerPoller.isRunning()) {
            producerPoller.stop();
        }
        scheduler.shutdownNow();
    }

    private void gracefulShutdown() {
        LOG.info("Duration elapsed, stopping poller and draining in-flight publishes.");
        producerPoller.stop();
        try {
            Thread.sleep(DRAIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        SpringApplication.exit(context, () -> 0);
    }
}
