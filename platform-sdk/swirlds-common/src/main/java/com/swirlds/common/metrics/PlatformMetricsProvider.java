// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An implementation of this class is responsible for creating {@link Metrics}-implementations.
 * <p>
 * The platform provides (at least) one default implementation, but if application developers want to use their
 * own implementations of {@code Metrics}, they have to set up their own provider.
 *
 * @param <KEY> the type of the unique identifier for separate instances of metrics
 */
public interface PlatformMetricsProvider<KEY> {

    /**
     * Creates the global {@link Metrics}-instance, that keeps global metrics.
     * <p>
     * During normal execution, there will be only one global {@code Metrics}, which will be shared between
     * all platforms. Accordingly, this method will be called only once.
     *
     * @return the new instance of {@code Metrics}
     */
    @NonNull
    Metrics createGlobalMetrics();

    /**
     * Creates a platform-specific {@link Metrics}-instance.
     *
     * @param key the unique identifier of the platform
     * @return the new instance of {@code Metrics}
     */
    @NonNull
    Metrics createPlatformMetrics(@NonNull KEY key);

    /**
     * Remove a platform-specific {@link Metrics}-instance.
     *
     * @param key the unique identifier of the platform
     */
    void removePlatformMetrics(@NonNull KEY key) throws InterruptedException;
}
