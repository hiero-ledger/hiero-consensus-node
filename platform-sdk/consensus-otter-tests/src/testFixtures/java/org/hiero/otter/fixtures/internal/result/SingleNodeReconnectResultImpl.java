// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;
import static org.hiero.otter.fixtures.internal.helpers.LogPayloadUtils.parsePayload;

import com.hedera.hapi.platform.state.NodeId;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.PlatformStatusSubscriber;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.jetbrains.annotations.NotNull;

/**
 * Implementation of the {@link SingleNodeReconnectResult} interface.
 */
public class SingleNodeReconnectResultImpl implements SingleNodeReconnectResult {

    private final NodeId nodeId;
    private final SingleNodePlatformStatusResult statusResults;
    private final SingleNodeLogResult logResults;

    /**
     * Constructor for SingleNodeReconnectResultImpl.
     *
     * @param statusResults the platform status results for the single node
     * @param logResults the log results for the single node
     */
    public SingleNodeReconnectResultImpl(
            @NonNull final NodeId nodeId,
            @NonNull final SingleNodePlatformStatusResult statusResults,
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
    @NonNull
    public List<SynchronizationCompletePayload> getSynchronizationCompletePayloads() {
        return logResults.logs().stream()
                .filter(log -> log.message().contains(SynchronizationCompletePayload.class.toString()))
                .map(log -> parsePayload(SynchronizationCompletePayload.class, log.message()))
                .toList();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NotNull final LogSubscriber subscriber) {
        logResults.subscribe(subscriber);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void subscribe(@NotNull final PlatformStatusSubscriber subscriber) {
        statusResults.subscribe(subscriber);
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
