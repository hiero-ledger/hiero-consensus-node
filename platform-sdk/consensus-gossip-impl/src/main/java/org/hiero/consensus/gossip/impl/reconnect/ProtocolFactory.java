// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.reconnect;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.gossip.ReservedSignedStateResult;
import org.hiero.consensus.gossip.impl.network.protocol.Protocol;
import org.hiero.consensus.monitoring.FallenBehindMonitor;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Factory for creating protocol instances. This is used to decouple the reconnect protocol creation.
 */
public interface ProtocolFactory {

    /**
     * Creates a new protocol instance.
     *
     * @param configuration the platform configuration
     * @param metrics the metrics system
     * @param time the source of time
     * @param threadManager the thread manager
     * @param latestCompleteState supplier for the latest complete reserved signed state
     * @param reservedSignedStateResultPromise blocking resource provider for reserved signed state results
     * @param fallenBehindMonitor the fallen behind monitor
     * @param stateLifecycleManager the state lifecycle manager
     * @return the created protocol
     */
    @NonNull
    Protocol createProtocol(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final ThreadManager threadManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager);
}
