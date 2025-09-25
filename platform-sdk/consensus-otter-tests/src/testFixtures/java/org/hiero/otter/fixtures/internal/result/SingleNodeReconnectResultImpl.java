// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.result;

import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;
import static org.hiero.otter.fixtures.internal.helpers.LogPayloadUtils.parsePayload;

import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.logging.legacy.payload.ReconnectStartPayload;
import com.swirlds.logging.legacy.payload.SynchronizationCompletePayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.ReconnectFailureNotification;
import org.hiero.otter.fixtures.result.ReconnectNotification;
import org.hiero.otter.fixtures.result.ReconnectNotificationSubscriber;
import org.hiero.otter.fixtures.result.ReconnectStartNotification;
import org.hiero.otter.fixtures.result.SingleNodeLogResult;
import org.hiero.otter.fixtures.result.SingleNodePlatformStatusResult;
import org.hiero.otter.fixtures.result.SingleNodeReconnectResult;
import org.hiero.otter.fixtures.result.SubscriberAction;
import org.hiero.otter.fixtures.result.SynchronizationCompleteNotification;

/**
 * Implementation of the {@link SingleNodeReconnectResult} interface.
 */
public class SingleNodeReconnectResultImpl implements SingleNodeReconnectResult {

    private final NodeId nodeId;
    private final SingleNodePlatformStatusResult statusResults;
    private final SingleNodeLogResult logResults;
    private final List<ReconnectNotificationSubscriber> reconnectSubscribers = new CopyOnWriteArrayList<>();

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
    @NonNull
    public NodeId nodeId() {
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
    public void subscribe(@NonNull final ReconnectNotificationSubscriber newSubscriber) {
        if (reconnectSubscribers.isEmpty()) {
            logResults.subscribe(this::onLogEntry);
        }
        reconnectSubscribers.add(newSubscriber);
    }

    @NonNull
    private SubscriberAction onLogEntry(@NonNull final StructuredLog logEntry) {
        final ReconnectNotification<?> notification = toReconnectNotification(logEntry);
        if (notification != null) {
            reconnectSubscribers.removeIf(
                    subscriber -> subscriber.onNotification(notification) == SubscriberAction.UNSUBSCRIBE);
            return reconnectSubscribers.isEmpty() ? SubscriberAction.UNSUBSCRIBE : SubscriberAction.CONTINUE;
        }
        return SubscriberAction.CONTINUE;
    }

    @Nullable
    private ReconnectNotification<?> toReconnectNotification(@NonNull final StructuredLog logEntry) {
        final String message = logEntry.message();
        if (message.contains(ReconnectFailurePayload.class.toString())) {
            return new ReconnectFailureNotification(
                    parsePayload(ReconnectFailurePayload.class, message), logEntry.nodeId());
        } else if (message.contains(ReconnectStartPayload.class.toString())) {
            return new ReconnectStartNotification(
                    parsePayload(ReconnectStartPayload.class, message), logEntry.nodeId());
        } else if (message.contains(SynchronizationCompletePayload.class.toString())) {
            return new SynchronizationCompleteNotification(
                    parsePayload(SynchronizationCompletePayload.class, message), logEntry.nodeId());
        }
        return null;
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
