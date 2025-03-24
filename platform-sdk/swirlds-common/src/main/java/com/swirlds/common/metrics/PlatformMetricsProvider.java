// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.node.NodeId;

/**
 * An implementation of this class is responsible for creating {@link Metrics}-implementations.
 * <p>
 * The platform provides (at least) one default implementation, but if application developers want to use their
 * own implementations of {@code Metrics}, they have to set up their own provider.
 */
public interface PlatformMetricsProvider {

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
     * @param selfId
     * 		the {@link NodeId} of the platform
     * @return the new instance of {@code Metrics}
     */
    @NonNull
    Metrics createPlatformMetrics(final @NonNull NodeId selfId);
}
