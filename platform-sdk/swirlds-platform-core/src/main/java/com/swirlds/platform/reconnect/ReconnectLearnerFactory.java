// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.platform.metrics.ReconnectMetrics;
import com.swirlds.platform.network.Connection;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;

/**
 * Creates instances of {@link ReconnectLearner}
 */
public class ReconnectLearnerFactory {
    private final Roster roster;
    private final Duration reconnectSocketTimeout;
    private final ReconnectMetrics statistics;
    private final ThreadManager threadManager;
    private final PlatformContext platformContext;
    private final PlatformStateFacade platformStateFacade;

    /**
     * @param platformContext the platform context
     * @param threadManager          responsible for managing thread lifecycles
     * @param roster                 the current roster
     * @param reconnectSocketTimeout the socket timeout to use during the reconnect
     * @param statistics             reconnect metrics
     * @param platformStateFacade    the facade to access the platform state
     */
    public ReconnectLearnerFactory(
            @NonNull final PlatformContext platformContext,
            @NonNull final ThreadManager threadManager,
            @NonNull final Roster roster,
            @NonNull final Duration reconnectSocketTimeout,
            @NonNull final ReconnectMetrics statistics,
            @NonNull final PlatformStateFacade platformStateFacade) {
        this.platformContext = Objects.requireNonNull(platformContext);
        this.threadManager = Objects.requireNonNull(threadManager);
        this.roster = Objects.requireNonNull(roster);
        this.reconnectSocketTimeout = Objects.requireNonNull(reconnectSocketTimeout);
        this.statistics = Objects.requireNonNull(statistics);
        this.platformStateFacade = platformStateFacade;
    }

    /**
     * Create an instance of {@link ReconnectLearner}
     *
     * @param conn         the connection to use
     * @param workingState the state to use to perform a delta based reconnect
     * @return a new instance
     */
    public ReconnectLearner create(final Connection conn, final MerkleNodeState workingState) {
        return new ReconnectLearner(
                platformContext,
                threadManager,
                conn,
                roster,
                workingState,
                reconnectSocketTimeout,
                statistics,
                platformStateFacade);
    }
}
