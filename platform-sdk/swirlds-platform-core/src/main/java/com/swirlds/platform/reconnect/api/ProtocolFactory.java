// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect.api;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.network.protocol.Protocol;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.state.StateLifecycleManager;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.base.concurrent.BlockingResourceProvider;
import org.hiero.consensus.concurrent.manager.ThreadManager;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

/**
 * Factory for creating protocol instances. This is used to decouple the reconnect protocol creation.
 */
public interface ProtocolFactory {

    /**
     * Creates a new protocol instance.
     *
     * @param platformContext the platform context
     * @param threadManager the thread manager
     * @param latestCompleteState supplier for the latest complete reserved signed state
     * @param reservedSignedStateResultPromise blocking resource provider for reserved signed state results
     * @param fallenBehindMonitor the fallen behind monitor
     * @param stateLifecycleManager the state lifecycle manager
     * @return the created protocol
     */
    @NonNull
    Protocol createProtocol(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Supplier<ReservedSignedState> latestCompleteState,
            @NonNull final BlockingResourceProvider<ReservedSignedStateResult> reservedSignedStateResultPromise,
            @NonNull final FallenBehindMonitor fallenBehindMonitor,
            @NonNull final StateLifecycleManager stateLifecycleManager);
}
