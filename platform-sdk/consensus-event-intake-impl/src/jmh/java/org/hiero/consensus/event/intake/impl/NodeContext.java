// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metrics;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.consensus.benchmark.tools.pipeline.UncaughtExceptionCollector;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.wiring.framework.WiringConfig;
import org.hiero.consensus.wiring.framework.model.WiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;

/**
 * What the modules of a node run on, set up as in production: the default configuration, the production metrics
 * implementation ({@link DefaultPlatformMetrics}, not started), the system clock, and a wiring model on a thread pool
 * of the production size. The exceptions thrown on the pool's threads are collected, so that a benchmark can fail on
 * them.
 *
 * <p>A benchmark initializes its modules on the context and attaches its timer before it calls {@link #start()}.
 */
final class NodeContext {

    /** The name of the thread group of the wiring model's threads, as in production. */
    private static final String THREAD_GROUP_NAME = "platform";

    private final Configuration configuration;
    private final DefaultPlatformMetrics metrics;
    private final Time time;
    private final ScheduledExecutorService metricsExecutor;
    private final ForkJoinPool pool;
    private final WiringModel model;
    private final UncaughtExceptionCollector exceptions;

    private NodeContext(
            final Configuration configuration,
            final DefaultPlatformMetrics metrics,
            final Time time,
            final ScheduledExecutorService metricsExecutor,
            final ForkJoinPool pool,
            final WiringModel model,
            final UncaughtExceptionCollector exceptions) {
        this.configuration = configuration;
        this.metrics = metrics;
        this.time = time;
        this.metricsExecutor = metricsExecutor;
        this.pool = pool;
        this.model = model;
        this.exceptions = exceptions;
    }

    /**
     * Creates the context of a node. The wiring model is not started.
     *
     * @param selfId the ID of the node, which the metrics need
     * @return the context
     */
    static NodeContext create(final NodeId selfId) {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();

        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        // The metrics require an executor; it stays idle, because the metrics are not started.
        final ScheduledExecutorService metricsExecutor = Executors.newSingleThreadScheduledExecutor();
        final DefaultPlatformMetrics metrics = new DefaultPlatformMetrics(
                selfId,
                new MetricKeyRegistry(),
                metricsExecutor,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        final Time time = Time.getCurrent();

        final UncaughtExceptionCollector exceptions = new UncaughtExceptionCollector();
        final WiringConfig wiringConfig = configuration.getConfigData(WiringConfig.class);
        // This is the same formula for the parallelism as in production.
        final int parallelism = (int) Math.max(
                1,
                wiringConfig.defaultPoolMultiplier() * Runtime.getRuntime().availableProcessors()
                        + wiringConfig.defaultPoolConstant());
        final ForkJoinPool pool =
                ExecutorFactory.create(THREAD_GROUP_NAME, exceptions).createForkJoinPool(parallelism);
        final WiringModel model = WiringModelBuilder.create(metrics, time)
                .withDefaultPool(pool)
                .withWiringConfig(wiringConfig)
                .withUncaughtExceptionHandler(exceptions)
                .build();

        return new NodeContext(configuration, metrics, time, metricsExecutor, pool, model, exceptions);
    }

    /**
     * @return the default configuration
     */
    Configuration configuration() {
        return configuration;
    }

    /**
     * @return the production metrics implementation, not started
     */
    Metrics metrics() {
        return metrics;
    }

    /**
     * @return the system clock
     */
    Time time() {
        return time;
    }

    /**
     * @return the wiring model, not started until {@link #start()}
     */
    WiringModel model() {
        return model;
    }

    /**
     * @return the collector of the exceptions thrown on the wiring model's threads
     */
    UncaughtExceptionCollector exceptions() {
        return exceptions;
    }

    /**
     * Starts the wiring model.
     */
    void start() {
        model.start();
    }

    /**
     * Stops the wiring model, its thread pool, and the metrics executor.
     */
    void stop() {
        model.stop();
        pool.shutdownNow();
        metricsExecutor.shutdownNow();
    }
}
