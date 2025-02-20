// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A {@link BlockingQueue blocking queue} that can be monitored using the given metrics configuration
 * whenever the queue is updated.
 *
 * <p>
 * Internally a new metric will be created and applied to the queue when it is enabled by configuration.
 */
class MeasuredBlockingQueue<T> extends AbstractBlockingQueue<T> {

    static final String QUEUE_MAX_SIZE_SUFFIX = "_queueMaxSize";
    static final String QUEUE_MIN_SIZE_SUFFIX = "_queueMinSize";

    private final IntegerAccumulator maxSizeMetric;
    private final IntegerAccumulator minSizeMetric;

    /**
     * Creates a {@code MeasuredBlockingQueue} with the given {@code queue} and the {@code config}.
     *
     * @param queue
     *         the queue to be handled by this wrapper
     * @param config
     *         the configuration for queue metrics to apply
     * @throws IllegalArgumentException
     *         if {@code queue} or {@code handler} is null
     * @throws IllegalStateException
     *         if no metrics are enabled in the configuration
     */
    public MeasuredBlockingQueue(@NonNull final BlockingQueue<T> queue, @NonNull final Config config) {
        super(queue);
        Objects.requireNonNull(queue, "queue must not be null");
        Objects.requireNonNull(config, "config must not be null");

        if (!config.isMetricEnabled()) {
            throw new IllegalStateException("No metrics have been enabled for the queue '" + config.queueName + "'");
        }

        if (config.maxSizeMetricEnabled) {
            final String name = config.queueName + QUEUE_MAX_SIZE_SUFFIX;
            final String description =
                    String.format("the maximum size during a sampling period of the queue %s", config.queueName);
            maxSizeMetric = config.metrics.getOrCreate(new IntegerAccumulator.Config(config.category, name)
                    .withDescription(description)
                    .withFormat("%d")
                    .withAccumulator(Math::max)
                    .withInitializer(queue::size));
        } else {
            maxSizeMetric = null;
        }

        if (config.minSizeMetricEnabled) {
            final String name = config.queueName + QUEUE_MIN_SIZE_SUFFIX;
            final String description =
                    String.format("the minimum size during a sampling period of the queue %s", config.queueName);
            minSizeMetric = config.metrics.getOrCreate(new IntegerAccumulator.Config(config.category, name)
                    .withDescription(description)
                    .withFormat("%d")
                    .withAccumulator(Math::min)
                    .withInitializer(queue::size));
        } else {
            minSizeMetric = null;
        }
    }

    private void updateMetrics() {
        if (maxSizeMetric != null) {
            maxSizeMetric.update(super.size());
        }
        if (minSizeMetric != null) {
            minSizeMetric.update(super.size());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(final T t) {
        final boolean changed = super.add(t);
        if (changed) {
            updateMetrics();
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean addAll(final Collection<? extends T> c) {
        final boolean changed = super.addAll(c);
        if (changed) {
            updateMetrics();
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean offer(final T t) {
        final boolean changed = super.offer(t);
        if (changed) {
            updateMetrics();
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean offer(final T t, final long timeout, final TimeUnit unit) throws InterruptedException {
        final boolean changed = super.offer(t, timeout, unit);
        if (changed) {
            updateMetrics();
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(final T t) throws InterruptedException {
        super.put(t);
        updateMetrics();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T poll() {
        final T t = super.poll();
        updateMetrics();
        return t;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T poll(final long timeout, final TimeUnit unit) throws InterruptedException {
        final T t = super.poll(timeout, unit);
        updateMetrics();
        return t;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T remove() {
        final T t = super.remove();
        updateMetrics();
        return t;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean remove(final Object o) {
        final boolean changed = super.remove(o);
        if (changed) {
            updateMetrics();
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeAll(final Collection<?> c) {
        final boolean changed = super.removeAll(c);
        if (changed) {
            updateMetrics();
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public T take() throws InterruptedException {
        final T t = super.take();
        updateMetrics();
        return t;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int drainTo(final Collection<? super T> c) {
        final int num = super.drainTo(c);
        updateMetrics();
        return num;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int drainTo(final Collection<? super T> c, final int maxElements) {
        final int num = super.drainTo(c, maxElements);
        updateMetrics();
        return num;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean retainAll(final Collection<?> c) {
        final boolean changed = super.retainAll(c);
        if (changed) {
            updateMetrics();
        }
        return changed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        super.clear();
        updateMetrics();
    }

    /**
     * Configuration for {@link MeasuredBlockingQueue}
     */
    static final class Config {

        private final Metrics metrics;
        private final String category;
        private final String queueName;
        private final boolean maxSizeMetricEnabled;
        private final boolean minSizeMetricEnabled;

        /**
         * Creates {@code MeasuredBlockingQueue.Config}.
         *
         * @param metrics
         *         the metrics-system
         * @param category
         *         the category of metric
         * @param queueName
         *         the name of the queue to monitor
         * @throws IllegalArgumentException
         *         if {@code metrics}, {@code category} or {@code queueName} is {@code null} or empty
         */
        public Config(final Metrics metrics, final String category, final String queueName) {
            this(metrics, category, queueName, false, false);
        }

        private Config(
                @NonNull final Metrics metrics,
                @NonNull final String category,
                @NonNull final String queueName,
                final boolean maxSizeMetricEnabled,
                final boolean minSizeMetricEnabled) {
            Objects.requireNonNull(metrics, "metrics must not be null");
            Objects.requireNonNull(category, "category must not be null");
            Objects.requireNonNull(queueName, "queueName must not be null");
            this.metrics = metrics;
            this.category = category;
            this.queueName = queueName;
            this.maxSizeMetricEnabled = maxSizeMetricEnabled;
            this.minSizeMetricEnabled = minSizeMetricEnabled;
        }

        /**
         * Fluent-style setter of {@code maxSizeMetricEnabled}
         *
         * @param enabled
         *         if true, the Max Size Metric will be enabled.
         * @return a new queue configuration
         */
        public Config withMaxSizeMetricEnabled(final boolean enabled) {
            return new Config(getMetrics(), getCategory(), getQueueName(), enabled, isMinSizeMetricEnabled());
        }

        /**
         * Fluent-style setter of {@code minSizeMetricEnabled}
         *
         * @param enabled
         *         if true, the Min Size Metric will be enabled.
         * @return a new queue configuration
         */
        public Config withMinSizeMetricEnabled(final boolean enabled) {
            return new Config(getMetrics(), getCategory(), getQueueName(), isMaxSizeMetricEnabled(), enabled);
        }

        /**
         * Getter of the {@code metrics}
         *
         * @return the metrics
         */
        public Metrics getMetrics() {
            return metrics;
        }

        /**
         * Getter of the {@code category}
         *
         * @return the category
         */
        public String getCategory() {
            return category;
        }

        /**
         * Getter of the {@code queueName}
         *
         * @return the queue name
         */
        public String getQueueName() {
            return queueName;
        }

        /**
         * Getter of the {@code maxSizeMetricEnabled}
         *
         * @return {@code true} if the Max Size Metric is enabled, {@code false} otherwise
         */
        public boolean isMaxSizeMetricEnabled() {
            return maxSizeMetricEnabled;
        }

        /**
         * Getter of the {@code minSizeMetricEnabled}
         *
         * @return {@code true} if the Min Size Metric is enabled, {@code false} otherwise
         */
        public boolean isMinSizeMetricEnabled() {
            return minSizeMetricEnabled;
        }

        /**
         * Checks if any of queue metrics is enabled.
         *
         * @return {@code true} if any metric is enabled, {@code false} otherwise
         */
        public boolean isMetricEnabled() {
            return maxSizeMetricEnabled || minSizeMetricEnabled;
        }
    }
}
