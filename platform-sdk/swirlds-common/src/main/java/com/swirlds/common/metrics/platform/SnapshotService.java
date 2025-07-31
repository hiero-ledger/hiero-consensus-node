// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.common.metrics.platform.DefaultPlatformMetrics.calculateMetricKey;

import com.swirlds.base.state.Startable;
import com.swirlds.base.time.Time;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.metrics.api.Snapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Service that creates new snapshots in regular intervals and sends a {@link SnapshotEvent}.
 * This is in particular used to write metrics-data to different file formats.
 * <p>
 * This class contains only the general functionality, handling the data is left to the different
 * receivers of {@code SnapshotEvents}.
 * <p>
 * This class uses a provided {@link java.util.concurrent.ExecutorService} that triggers the snapshot creation.
 * The frequency of these write operations can be configured with {@link MetricsConfig#getMetricsSnapshotDuration()}.
 * <p>
 * The service is not automatically started, but has to be started manually with {@link #start()}. When done,
 * the service can be shutdown with {@link #shutdown()}.
 *
 * @see LegacyCsvWriter
 * @see com.swirlds.common.metrics.platform.prometheus.PrometheusEndpoint
 */
public class SnapshotService implements Startable {

    private static final Logger logger = LogManager.getLogger(SnapshotService.class);

    private final DefaultPlatformMetrics globalMetrics;
    private final Queue<DefaultPlatformMetrics> platformMetricsList = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService executor;
    private final Queue<Consumer<? super SnapshotEvent>> subscribers = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final Time time;
    private final long delayNanos;

    /**
     * Constructor of {@code SnapshotService}.
     * <p>
     * The service is created, but not automatically started. It can be started with {@link #start()}.
     *
     * @param globalMetrics
     * 		a list of {@link Metric}-instances which values need to be written
     * @param executor
     * 		the {@link ScheduledExecutorService} that will be used to schedule the writer-tasks
     * @param interval
     * 		interval between snapshot generations
     * @throws NullPointerException if any of the following parameters are {@code null}.
     *     <ul>
     *       <li>{@code globalMetrics}</li>
     *       <li>{@code executor}</li>
     *       <li>{@code interval}</li>
     *     </ul>
     *
     */
    public SnapshotService(
            final DefaultPlatformMetrics globalMetrics,
            final ScheduledExecutorService executor,
            final Duration interval) {
        this(globalMetrics, executor, interval, Time.getCurrent());
    }

    // This method is just for testing and will be removed from the public API at some point.
    public SnapshotService(
            final DefaultPlatformMetrics globalMetrics,
            final ScheduledExecutorService executor,
            final Duration interval,
            final Time time) {
        this.globalMetrics = Objects.requireNonNull(globalMetrics, "globalMetrics must not be null");
        if (!globalMetrics.isGlobalMetrics()) {
            throw new IllegalArgumentException("Trying to create SnapshotService with non-global Metrics");
        }
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.delayNanos =
                Objects.requireNonNull(interval, "interval must not be null").toNanos();
        this.time = Objects.requireNonNull(time, "time must not be null");

        logger.debug("SnapshotService initialized");
    }

    /**
     * Add a platform-specific {@link Metrics} to the {@code SnapshotService}
     *
     * @param platformMetrics
     * 		the {@link DefaultPlatformMetrics} to add
     * @throws IllegalArgumentException
     * 		if {@code platformMetrics} is {@code null} or not platform-specific
     */
    public void addPlatformMetric(final DefaultPlatformMetrics platformMetrics) {
        Objects.requireNonNull(platformMetrics, "platformMetric must not be null");
        if (!platformMetrics.isPlatformMetrics()) {
            throw new IllegalArgumentException("Trying to add non-platform Metrics");
        }

        logger.debug("Adding platform Metrics {}", platformMetrics);
        this.platformMetricsList.add(platformMetrics);
    }

    /**
     * Remove a platform-specific {@link Metrics} from the {@code SnapshotService}
     *
     * @param platformMetrics
     * 		the {@link DefaultPlatformMetrics} to remove
     * @throws IllegalArgumentException
     * 		if {@code platformMetrics} is {@code null} or not platform-specific
     */
    public void removePlatformMetric(final DefaultPlatformMetrics platformMetrics) {
        Objects.requireNonNull(platformMetrics, "platformMetric must not be null");
        if (!platformMetrics.isPlatformMetrics()) {
            throw new IllegalArgumentException("Trying to remove non-platform Metrics");
        }

        logger.debug("Removing platform Metrics {}", platformMetrics);
        this.platformMetricsList.remove(platformMetrics);
    }

    /**
     * Checks if the service is running
     *
     * @return {@code true}, if the service is running, {@code false} otherwise
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Starts the service
     *
     * @throws IllegalStateException
     * 		if the service is running or was even shutdown already
     */
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("The snapshot-service is already running");
        }

        logger.debug("Starting SnapshotService");
        executor.schedule(this::mainLoop, delayNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * Requests the shutdown of the service.
     * <p>
     * Calling this method after the service was already shutdown is possible and should have no effect.
     */
    public void shutdown() {
        logger.debug("Shutting down snapshotService");
        running.set(false);
        executor.shutdown();
    }

    /**
     * Adds a subscriber that will receive snapshots in regular intervals.
     *
     * @param subscriber
     * 		the new {@code subscriber}
     * @return a {@link Runnable} that, when called, unsubscribes the subscriber
     */
    public Runnable subscribe(final Consumer<? super SnapshotEvent> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    private void mainLoop() {
        if (!isRunning()) {
            return;
        }
        logger.trace("Running mainLoop");
        final long start = time.nanoTime();

        final Map<String, Snapshot> globalSnapshots = globalMetrics.getAll().stream()
                .map(Snapshot::of)
                .collect(Collectors.toMap(snapshot -> calculateMetricKey(snapshot.metric()), Function.identity()));

        logger.trace(() -> String.format("Created %d global snapshots", globalSnapshots.size()));

        final SnapshotEvent globalEvent = new SnapshotEvent(null, globalSnapshots.values());
        subscribers.forEach(subscriber -> subscriber.accept(globalEvent));

        for (final DefaultPlatformMetrics platformMetrics : platformMetricsList) {
            final List<Snapshot> platformSnapshots = platformMetrics.getAll().stream()
                    .map(metric -> globalSnapshots.getOrDefault(calculateMetricKey(metric), Snapshot.of(metric)))
                    .toList();

            logger.trace(() -> String.format(
                    "Created %d snapshots for node %s", platformSnapshots.size(), platformMetrics.getNodeId()));

            final SnapshotEvent platformEvent = new SnapshotEvent(platformMetrics.getNodeId(), platformSnapshots);
            subscribers.forEach(subscriber -> subscriber.accept(platformEvent));
        }

        final long delta = time.nanoTime() - start;
        logger.trace("Running mainLoop took {} ns", delta);

        // schedule next execution
        try {
            executor.schedule(this::mainLoop, Math.max(0L, delayNanos - delta), TimeUnit.NANOSECONDS);
        } catch (final RejectedExecutionException ex) {
            // executor was shutdown
        }
    }
}
