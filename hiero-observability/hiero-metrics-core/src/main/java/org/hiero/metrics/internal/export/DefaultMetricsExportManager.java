// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.hiero.metrics.api.LongGauge;
import org.hiero.metrics.api.core.MetricRegistry;
import org.hiero.metrics.api.export.MetricsExportException;
import org.hiero.metrics.api.export.MetricsExporter;
import org.hiero.metrics.api.export.PullingMetricsExporter;
import org.hiero.metrics.api.export.PushingMetricsExporter;
import org.hiero.metrics.api.export.snapshot.MetricsSnapshot;
import org.hiero.metrics.api.utils.Unit;

/**
 * A default implementation of the {@link org.hiero.metrics.api.export.MetricsExportManager} interface
 * that supports multiple pulling and pushing exporters.
 * <p>
 * Pulling exporters are initialized during the manager's {@link #init()} method call and are provided with a
 * supplier of the latest metrics snapshot. They can pull the latest snapshot whenever they need it.
 * <p>
 * Pushing exporters are periodically invoked in a separate thread at a fixed interval to push the latest metrics
 * snapshot. The interval is configurable during the manager's construction. See {@link ExportRunnable}.
 * <p>
 * The manager ensures thread-safety when taking snapshots and updating the reference for pulling exporters.
 * It also handles exceptions thrown by exporters during initialization and exporting, logging errors without
 * disrupting the overall exporting process.
 */
public class DefaultMetricsExportManager extends AbstractMetricsExportManager {

    private static final String PUSHING_EXPORTER_NAME = "name";

    private final List<PullingMetricsExporter> pullingExporters;
    private final List<PushingMetricsExporter> pushingExporters;

    private final AtomicReference<Optional<MetricsSnapshot>> snapshotHolder = new AtomicReference<>(Optional.empty());

    private final Supplier<ScheduledExecutorService> executorServiceFactory;
    private final int exportIntervalSeconds;
    private volatile ScheduledFuture<?> scheduledExportFuture;

    // this metric will report previous export duration for each pushing exporter
    private LongGauge pushingExportDurationMetric;

    public DefaultMetricsExportManager(
            @NonNull String name,
            @NonNull Supplier<ScheduledExecutorService> executorServiceFactory,
            int exportIntervalSeconds,
            @NonNull List<PullingMetricsExporter> pullingExporters,
            @NonNull List<PushingMetricsExporter> pushingExporters) {
        super(name);

        if (exportIntervalSeconds <= 0) {
            throw new IllegalArgumentException("Export interval must be greater than 0 seconds");
        }

        this.executorServiceFactory =
                Objects.requireNonNull(executorServiceFactory, "executor service factory must not be null");
        this.exportIntervalSeconds = exportIntervalSeconds;

        this.pullingExporters =
                List.copyOf(Objects.requireNonNull(pullingExporters, "pulling exporters must not be null"));
        this.pushingExporters =
                List.copyOf(Objects.requireNonNull(pushingExporters, "pushing exporters must not be null"));

        if (pullingExporters.isEmpty() && pushingExporters.isEmpty()) {
            throw new IllegalArgumentException("At least one pulling or pushing exporter must be configured");
        }

        logExporters("pulling", pullingExporters);
        logExporters("pushing", pushingExporters);
    }

    private void logExporters(String type, List<? extends MetricsExporter> exporters) {
        if (exporters.isEmpty()) {
            logger.info("No {} exporters provided", type);
        } else {
            logger.info(
                    "Provided {} {} exporters: {}",
                    exporters.size(),
                    type,
                    exporters.stream().map(MetricsExporter::name).toList());
        }
    }

    @Override
    protected void registerExportMetrics(@NonNull String category, @NonNull MetricRegistry exportMetricsRegistry) {
        super.registerExportMetrics(category, exportMetricsRegistry);

        if (!pushingExporters.isEmpty()) {
            pushingExportDurationMetric = exportMetricsRegistry.register(
                    LongGauge.builder(LongGauge.key("push_export_duration").withCategory(category))
                            .withDescription("Push export duration time")
                            .withDynamicLabelNames(PUSHING_EXPORTER_NAME)
                            .withUnit(Unit.MILLISECOND_UNIT));
        }
    }

    @Override
    protected void init() {
        for (PullingMetricsExporter pullingExporter : pullingExporters) {
            try {
                logger.info("Initializing pulling exporter: {}", pullingExporter.name());
                pullingExporter.init(snapshotHolder::get);
            } catch (RuntimeException e) {
                logger.error(
                        "Error while initializing pulling metrics exporter {}. Ignoring it", pullingExporter.name(), e);
            }
        }

        logger.info("Scheduling periodic exporting with interval of {} seconds", exportIntervalSeconds);
        scheduledExportFuture = executorServiceFactory
                .get()
                .scheduleAtFixedRate(new ExportRunnable(), 0, exportIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public boolean hasRunningExportThread() {
        return scheduledExportFuture != null;
    }

    @Override
    public synchronized void shutdown() {
        if (scheduledExportFuture != null && !scheduledExportFuture.isDone()) {
            scheduledExportFuture.cancel(false);
            scheduledExportFuture = null;

            for (PullingMetricsExporter pullingExporter : pullingExporters) {
                try {
                    pullingExporter.close();
                } catch (Exception ex) {
                    logger.error("Error closing pulling metrics exporter {}", pullingExporter.name(), ex);
                    // ignore, we are stopping anyway
                }
            }

            for (PushingMetricsExporter pushingExporter : pushingExporters) {
                try {
                    pushingExporter.close();
                } catch (Exception ex) {
                    logger.error("Error closing pushing metrics exporter {}", pushingExporter.name(), ex);
                    // ignore, we are stopping anyway
                }
            }
        }
    }

    /**
     * A runnable that takes a snapshot, updates reference for pulling exporters and pushes it to all pushing exporters.
     */
    private class ExportRunnable implements Runnable {

        @Override
        public void run() {
            final Optional<MetricsSnapshot> snapshotOptional = takeSnapshot();
            snapshotHolder.set(snapshotOptional);

            if (snapshotOptional.isEmpty() || pushingExporters.isEmpty()) {
                return;
            }

            final MetricsSnapshot snapshot = snapshotOptional.get();
            for (PushingMetricsExporter pushingExporter : pushingExporters) {
                final long startTime = System.currentTimeMillis();
                try {
                    pushingExporter.export(snapshot);
                } catch (MetricsExportException ex) {
                    // TODO disable and enable back after some time, completely remove after some time in disabled state
                    logger.error(
                            "Error while exporting metrics snapshot by pushing metrics exporter {}",
                            pushingExporter.name(),
                            ex);
                } catch (Throwable ex) {
                    // TODO remove from pushing exporters list ?
                    logger.error(
                            "Error while exporting metrics snapshot by pushing metrics exporter {}",
                            pushingExporter.name(),
                            ex);
                } finally {
                    final long duration = System.currentTimeMillis() - startTime;
                    pushingExportDurationMetric
                            .getOrCreateLabeled(PUSHING_EXPORTER_NAME, pushingExporter.name())
                            .update(duration);
                }
            }
        }
    }
}
