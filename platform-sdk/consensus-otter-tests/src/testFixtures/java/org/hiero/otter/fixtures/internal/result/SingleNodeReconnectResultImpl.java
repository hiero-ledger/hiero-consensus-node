// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.jetbrains.annotations.NotNull;

/**
 * Implementation of the {@link SingleNodeReconnectResult} interface.
 */
public class SingleNodeReconnectResultImpl implements SingleNodeReconnectResult {

    private final NodeId nodeId;
    private final SingleNodePlatformStatusResults statusResults;
    private final SingleNodeLogResult logResults;

    /**
     * Constructor for SingleNodeReconnectResultImpl.
     * @param statusResults the platform status results for the single node
     * @param logResults the log results for the single node
     */
    public SingleNodeReconnectResultImpl(
            @NonNull final NodeId nodeId,
            @NonNull final SingleNodePlatformStatusResults statusResults,
            @NonNull final SingleNodeLogResult logResults) {
        this.nodeId = requireNonNull(nodeId);
        this.statusResults = requireNonNull(statusResults);
        this.logResults = requireNonNull(logResults);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NotNull com.hedera.hapi.platform.state.NodeId nodeId() {
        return nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int numSuccessfulReconnects() {
        return (int) statusResults.statusProgression().stream()
                .filter(RECONNECT_COMPLETE::equals)
                .count();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int numFailedReconnects() {
        return (int) logResults.logs().stream()
                .filter(log -> log.message().contains(ReconnectFailurePayload.class.toString()))
                .count();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        statusResults.clear();
        logResults.clear();
    }
}
