// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CHANNEL_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_CONNECTOR_NOT_FOUND;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INVALID_CHANNEL_STATUS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NOT_ENABLED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PAYLOAD_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_QUEUE_FULL;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_QUEUE_QUOTA_EXCEEDED;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConfigUpdate;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.node.app.service.clpr.ClprServiceApi;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Implementation of {@link ClprServiceApi} that directly accesses CLPR writable state
 * to enqueue cross-ledger messages.
 */
public class ClprServiceApiImpl implements ClprServiceApi {
    private static final Logger log = LogManager.getLogger(ClprServiceApiImpl.class);

    private final ReadableChannelStoreImpl channelReadStore;
    private final WritableChannelStore channelWriteStore;
    private final WritableConnectorStore connectorStore;
    private final ReadableLedgerConfigurationStoreImpl configStore;
    private final WritableMessageQueueStore messageQueueStore;
    private final Configuration configuration;

    public ClprServiceApiImpl(
            @NonNull final ReadableChannelStoreImpl channelReadStore,
            @NonNull final WritableChannelStore channelWriteStore,
            @NonNull final WritableConnectorStore connectorStore,
            @NonNull final ReadableLedgerConfigurationStoreImpl configStore,
            @NonNull final WritableMessageQueueStore messageQueueStore,
            @NonNull final Configuration configuration) {
        this.channelReadStore = requireNonNull(channelReadStore);
        this.channelWriteStore = requireNonNull(channelWriteStore);
        this.connectorStore = requireNonNull(connectorStore);
        this.configStore = requireNonNull(configStore);
        this.messageQueueStore = requireNonNull(messageQueueStore);
        this.configuration = requireNonNull(configuration);
    }

    @Override
    public long sendMessage(
            @NonNull final Bytes channelId,
            @NonNull final Bytes connectorId,
            @NonNull final Bytes targetApplication,
            @NonNull final Bytes sender,
            @NonNull final Bytes messageData) {
        requireNonNull(channelId);
        requireNonNull(connectorId);
        requireNonNull(targetApplication);
        requireNonNull(sender);
        requireNonNull(messageData);

        if (!configuration.getConfigData(ClprConfig.class).enabled()) {
            throw new HandleException(CLPR_NOT_ENABLED);
        }

        // Step 1: Look up Channel — reject if not found or status != ACTIVE
        final var channel = channelReadStore.getChannel(channelId);
        if (channel == null) {
            throw new HandleException(CLPR_CHANNEL_NOT_FOUND);
        }
        if (channel.status() != ClprChannelStatus.ACTIVE) {
            throw new HandleException(CLPR_INVALID_CHANNEL_STATUS);
        }

        // Step 2: Lazy config propagation — check if Channel's config is stale
        final var ledgerConfig = configStore.getConfiguration();
        final var configTimestamp = ledgerConfig.timestamp();
        final var lastConfigTimestamp = channel.lastConfigTimestamp();
        final boolean configIsStale = isTimestampBefore(lastConfigTimestamp, configTimestamp);

        // Step 3: Look up Connector by connector_id
        final var connectorKey = new ClprConnectorKey(channelId, connectorId);
        final var connector = connectorStore.getConnector(connectorKey);
        if (connector == null) {
            throw new HandleException(CLPR_CONNECTOR_NOT_FOUND);
        }

        // Step 4: Connector authorization is enforced upstream in SendMessageCall
        // via a static sub-call to IClprConnector.authorizeOutboundMessage before this method is invoked.

        // Step 5: Validate payload size against the peer's limit — the destination is authoritative
        // for its own max_message_payload_bytes. Fall back to local config if peer throttles are absent
        // (e.g., for channels established before this field was introduced).
        final var localThrottles = ledgerConfig.throttles();
        final var peerThrottles = channel.peerThrottles();
        final var effectiveMaxPayload = (peerThrottles != null && peerThrottles.maxMessagePayloadBytes() > 0)
                ? peerThrottles.maxMessagePayloadBytes()
                : localThrottles.maxMessagePayloadBytes();
        if (messageData.length() > effectiveMaxPayload) {
            throw new HandleException(CLPR_PAYLOAD_TOO_LARGE);
        }

        // Step 6: Validate queue depth — reserve an extra slot when config is stale,
        // since a config-update control message will be prepended before the data message.
        final var nextMessageId = channel.nextMessageId();
        final var ackedMessageId = channel.ackedMessageId();
        final var maxQueueDepth = localThrottles.maxQueueDepth();
        final var queueDepth = nextMessageId - ackedMessageId;
        final var reservedSlots = configIsStale ? 1 : 0;
        log.debug(
                "[CLPR-SEND] request conn={} connectorId={} targetApplication={} sender={} dataLen={} "
                        + "status={} nextMsgId={} ackedMsgId={} queueDepth={} reservedSlots={} "
                        + "maxQueueDepth={} configStale={} sentRH={}",
                channelId,
                connectorId,
                targetApplication,
                sender,
                messageData.length(),
                channel.status(),
                nextMessageId,
                ackedMessageId,
                queueDepth,
                reservedSlots,
                maxQueueDepth,
                configIsStale,
                shortHex(channel.sentRunningHash()));
        if (queueDepth + reservedSlots >= maxQueueDepth) {
            log.warn(
                    "[CLPR-SEND] queue full conn={} queueDepth={} reservedSlots={} maxQueueDepth={} "
                            + "nextMsgId={} ackedMsgId={}",
                    channelId,
                    queueDepth,
                    reservedSlots,
                    maxQueueDepth,
                    nextMessageId,
                    ackedMessageId);
            throw new HandleException(CLPR_QUEUE_FULL);
        }

        // Step 6: Per-Connector queue quota check (CLPR-3.5)
        final var clprConfig = configuration.getConfigData(ClprConfig.class);
        final var connectorQuota = (long) maxQueueDepth * clprConfig.connectorQueueQuotaPct() / 100;
        final var connectorMessageCount =
                countConnectorMessages(channelId, ackedMessageId, nextMessageId, connector.connectorId());
        log.debug(
                "[CLPR-SEND] connector quota conn={} connectorId={} queuedForConnector={} "
                        + "connectorQuota={} inFlightBefore={}",
                channelId,
                connector.connectorId(),
                connectorMessageCount,
                connectorQuota,
                connector.inFlightMessageCount());
        if (connectorQuota > 0 && connectorMessageCount >= connectorQuota) {
            log.warn(
                    "[CLPR-SEND] connector quota exceeded conn={} connectorId={} queuedForConnector={} "
                            + "connectorQuota={}",
                    channelId,
                    connector.connectorId(),
                    connectorMessageCount,
                    connectorQuota);
            throw new HandleException(CLPR_QUEUE_QUOTA_EXCEEDED);
        }

        var currentRunningHash = channel.sentRunningHash();
        var currentNextMessageId = nextMessageId;
        var updatedLastConfigTimestamp = lastConfigTimestamp;

        // Step 2 (continued): If config is stale, enqueue a ConfigUpdate control message
        if (configIsStale) {
            final var configUpdate =
                    ClprConfigUpdate.newBuilder().configuration(ledgerConfig).build();
            final var controlMessage =
                    ClprControlMessage.newBuilder().configUpdate(configUpdate).build();
            final var controlPayload =
                    ClprMessagePayload.newBuilder().control(controlMessage).build();

            currentRunningHash = computeRunningHash(currentRunningHash, controlPayload);

            final var controlMessageValue = ClprMessageValue.newBuilder()
                    .payload(controlPayload)
                    .runningHashAfterProcessing(currentRunningHash)
                    .build();
            messageQueueStore.put(channelId, currentNextMessageId, controlMessageValue);
            log.debug(
                    "[CLPR-SEND] enqueued config update conn={} assignedMsgId={} runningHash={} "
                            + "configTimestamp={} nextMsgIdAfter={}",
                    channelId,
                    currentNextMessageId,
                    shortHex(currentRunningHash),
                    configTimestamp,
                    currentNextMessageId + 1);
            currentNextMessageId++;
            updatedLastConfigTimestamp = configTimestamp;
        }

        // Steps 7-8: Construct ClprMessage and wrap in ClprMessagePayload
        final var clprMessage = ClprMessage.newBuilder()
                .connectorId(connector.connectorId())
                .targetApplication(targetApplication)
                .sender(sender)
                .messageData(messageData)
                .build();
        final var dataPayload =
                ClprMessagePayload.newBuilder().message(clprMessage).build();

        // Step 9: Compute running hash
        final var previousRunningHash = currentRunningHash;
        final var newRunningHash = computeRunningHash(currentRunningHash, dataPayload);

        // Step 10: Store message in queue
        final var messageValue = ClprMessageValue.newBuilder()
                .payload(dataPayload)
                .runningHashAfterProcessing(newRunningHash)
                .build();
        final var assignedMessageId = currentNextMessageId;
        messageQueueStore.put(channelId, assignedMessageId, messageValue);
        log.debug(
                "[CLPR-SEND] enqueued outbound data conn={} assignedMsgId={} connectorId={} "
                        + "targetApplication={} sender={} dataLen={} previousRH={} newRH={} nextMsgIdAfter={}",
                channelId,
                assignedMessageId,
                connector.connectorId(),
                targetApplication,
                sender,
                messageData.length(),
                shortHex(previousRunningHash),
                shortHex(newRunningHash),
                assignedMessageId + 1);

        // Step 11: Update Channel
        final var updatedChannel = channel.copyBuilder()
                .nextMessageId(assignedMessageId + 1)
                .sentRunningHash(newRunningHash)
                .lastConfigTimestamp(updatedLastConfigTimestamp)
                .build();
        channelWriteStore.put(updatedChannel);

        // Step 12: Increment in-flight counter so deregister is blocked until a terminal reply arrives.
        connectorStore.put(connector
                .copyBuilder()
                .inFlightMessageCount(connector.inFlightMessageCount() + 1)
                .build());
        log.debug(
                "[CLPR-SEND] complete conn={} assignedMsgId={} nextMsgId={} ackedMsgId={} "
                        + "sentRH={} connectorId={} inFlightBefore={} inFlightAfter={}",
                channelId,
                assignedMessageId,
                updatedChannel.nextMessageId(),
                updatedChannel.ackedMessageId(),
                shortHex(updatedChannel.sentRunningHash()),
                connector.connectorId(),
                connector.inFlightMessageCount(),
                connector.inFlightMessageCount() + 1);

        // Step 13: Return assigned message_id
        return assignedMessageId;
    }

    /**
     * Computes SHA-256(previousHash || serializedPayload).
     * Delegates to {@link ClprHashUtils#computeRunningHash}.
     */
    @NonNull
    static Bytes computeRunningHash(@NonNull final Bytes previousHash, @NonNull final ClprMessagePayload payload) {
        return ClprHashUtils.computeRunningHash(previousHash, payload);
    }

    /**
     * Counts unacknowledged data messages in the queue belonging to the specified connector.
     */
    private long countConnectorMessages(
            @NonNull final Bytes channelId,
            final long ackedMessageId,
            final long nextMessageId,
            @NonNull final Bytes connectorId) {
        long count = 0;
        for (long id = ackedMessageId + 1; id < nextMessageId; id++) {
            final var value = messageQueueStore.getMessage(channelId, id);
            if (log.isDebugEnabled()) {
                final var payload = value != null && value.hasPayload() ? value.payload() : null;
                log.debug(
                        "[CLPR-QUEUE-READ] connector quota scan conn={} messageId={} present={} kind={}",
                        channelId,
                        id,
                        value != null,
                        payloadKind(payload));
            }
            if (value != null && value.payload() != null && value.payload().message() != null) {
                if (connectorId.equals(value.payload().message().connectorId())) {
                    count++;
                }
            }
        }
        log.debug(
                "[CLPR-QUEUE-READ] connector quota scan complete conn={} connectorId={} "
                        + "rangeStart={} rangeEndExclusive={} matched={}",
                channelId,
                connectorId,
                ackedMessageId + 1,
                nextMessageId,
                count);
        return count;
    }

    /**
     * Checks whether timestamp a is strictly before timestamp b.
     */
    private static boolean isTimestampBefore(final Timestamp a, final Timestamp b) {
        if (a == null || b == null) return a == null && b != null;
        if (a.seconds() != b.seconds()) return a.seconds() < b.seconds();
        return a.nanos() < b.nanos();
    }

    private static String payloadKind(@Nullable final ClprMessagePayload payload) {
        if (payload == null) {
            return "<none>";
        } else if (payload.hasMessage()) {
            return "DATA";
        } else if (payload.hasMessageReply()) {
            return "MESSAGE_REPLY";
        } else if (payload.hasControl()) {
            return "CONTROL";
        } else if (payload.hasRedactedMessage()) {
            return "REDACTED";
        } else {
            return "EMPTY";
        }
    }

    private static String shortHex(@Nullable final Bytes bytes) {
        if (bytes == null) {
            return "<null>";
        }
        final var hex = bytes.toHex();
        return hex.length() <= 64 ? hex : hex.substring(0, 64) + "...";
    }
}
