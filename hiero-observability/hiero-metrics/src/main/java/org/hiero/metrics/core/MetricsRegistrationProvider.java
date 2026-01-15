// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;

/**
 * An SPI for providing metrics to register in a {@link MetricRegistry}.<br>
 * Can be used when metrics are observed in different places and need to be registered in one place.
 * <p>
 * One implementation of this interface must exist per module that wants to register metrics.
 * The implementation class must have no-arg constructor and be registered in one of these ways:
 * <ul>
 *  <li>in the file {@code META-INF/services/org.hiero.metrics.core.MetricsRegistrationProvider} of the module</li>
 *  <li>(<b>preferred</b>) in the file {@code module-info.java} of the module as
 *  {@code provides org.hiero.metrics.core.MetricsRegistrationProvider with org.hiero.mymodule.MyMetricsRegistrationProvider}</li>
 * </ul>
 * <p>
 * Implementations will be discovered by {@link java.util.ServiceLoader} when creating a {@link MetricRegistry}
 * with {@link MetricRegistry.Builder#discoverMetricProviders()} activated.
 * <p>
 * When registered metrics are used in multiple places, {@link MetricKey}'s can be saved in {@code public static final}
 * fields and used to retrieve the metrics from the registry propagated to the place where the metric is used. <br>
 * {@link MetricsBinder} can be used as standard interface to propagate the registry to different places from main class
 * where usually metrics registry will be created.
 *
 * @see MetricsBinder
 */
public interface MetricsRegistrationProvider {

    /**
     * @return a collection of metric builders to register, never {@code null}
     */
    @NonNull
    Collection<Metric.Builder<?, ?>> getMetricsToRegister();
}
