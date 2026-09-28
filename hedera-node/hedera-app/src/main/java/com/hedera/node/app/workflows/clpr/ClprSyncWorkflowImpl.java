// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsRequest;
import com.hedera.hapi.node.state.clpr.ClprDiscoverEndpointsResponse;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ReadableEndpointManifestStoreImpl;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Server-side implementation of {@link ClprSyncWorkflow}. Handles incoming sync
 * requests from peer endpoints by:
 * <ol>
 *   <li>Parsing the incoming {@link ClprSyncPayload}</li>
 *   <li>Validating the channel exists and is ACTIVE</li>
 *   <li>Reading outbound messages from the queue</li>
 *   <li>Constructing a response payload with queue metadata and messages</li>
 *   <li>Serializing the response</li>
 * </ol>
 *
 * <p>Proof construction is performed by {@link ClprStateProofManager}; TSS signature
 * verification is Phase 2. Inbound bundle submission is implemented via {@link ClprBundleSubmitter}.
 */
@Singleton
public final class ClprSyncWorkflowImpl implements ClprSyncWorkflow {
    private static final Logger logger = LogManager.getLogger(ClprSyncWorkflowImpl.class);

    private final ConfigProvider configProvider;
    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final ClprBundleSubmitter bundleSubmitter;
    private final ClprChannelManager channelManager;
    private final ClprStateProofManager stateProofManager;

    @Inject
    public ClprSyncWorkflowImpl(
            @NonNull final ConfigProvider configProvider,
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final ClprBundleSubmitter bundleSubmitter,
            @NonNull final ClprChannelManager channelManager,
            @NonNull final ClprStateProofManager stateProofManager) {
        this.configProvider = requireNonNull(configProvider);
        this.stateAccessor = requireNonNull(stateAccessor);
        this.bundleSubmitter = requireNonNull(bundleSubmitter);
        this.channelManager = requireNonNull(channelManager);
        this.stateProofManager = requireNonNull(stateProofManager);
    }

    @Override
    public void handleSync(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
        requireNonNull(requestBytes);
        requireNonNull(responseBuffer);
        // 1. Check if CLPR is enabled
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("CLPR is not enabled"));
        }

        // 2. Parse the incoming sync payload
        final ClprSyncPayload request;
        try {
            request = ClprSyncPayload.PROTOBUF.parse(requestBytes);
        } catch (final Exception e) {
            logger.warn("Failed to parse ClprSyncPayload", e);
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("Invalid ClprSyncPayload: " + e.getMessage()));
        }

        final var channelId = request.channelId();
        logger.debug(
                "[CLPR-SYNC-INBOUND] request received conn={} requestBytes={} bundleBytes={}",
                channelId,
                requestBytes.length(),
                request.bundlePayload().length());
        if (channelId.length() != 32) {
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("channel_id must be exactly 32 bytes"));
        }

        // 3. Access latest immutable state and validate the channel
        try (final var wrappedState = stateAccessor.get()) {
            final var state = wrappedState.get();
            final var storeFactory = new ReadableStoreFactoryImpl(state);

            final var channelStore = storeFactory.readableStore(ReadableChannelStore.class);
            final var channel = channelStore.getChannel(channelId);
            if (channel == null) {
                throw new StatusRuntimeException(Status.NOT_FOUND.withDescription("Channel not found: " + channelId));
            }
            if (channel.status() == ClprChannelStatus.CLOSED || channel.status() == ClprChannelStatus.PENDING) {
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(
                        "Channel is not eligible for sync, status=" + channel.status()));
            }

            // Local manifest version drives the peer-staleness signal (see #335). If the peer's
            // cached version is behind ours, buildResponsePayload asks the state-proof builder
            // to embed a manifest leaf so Step 1b refreshes the peer's cache. The requester's
            // observed version of OUR manifest is read from the node-local record populated by
            // prior inbound bundles (ClprSubmitBundle Step 1b) — the same signal the outbound
            // initiator consumes, so both directions agree on when the peer is behind.
            final var localManifestStore =
                    new ReadableEndpointManifestStoreImpl(state.getReadableStates(ClprService.NAME));
            final long localManifestVersion = localManifestStore.get().version();
            final long requesterObservedManifestVersion = channelManager.peerObservedManifestVersion(channelId);
            final var response = buildResponsePayload(
                    channel,
                    channelId,
                    channel.peerThrottlesOrThrow(),
                    localManifestVersion,
                    requesterObservedManifestVersion);

            // 4. Serialize the response to the buffer
            final var responseBytes = ClprSyncPayload.PROTOBUF.toBytes(response);
            responseBuffer.writeBytes(responseBytes);
            logger.debug(
                    "[CLPR-SYNC-INBOUND] response written conn={} responseBytes={} bundleBytes={}",
                    channelId,
                    responseBytes.length(),
                    response.bundlePayload().length());
        }

        // Submit the inbound bundle as a ClprSubmitBundle transaction for consensus processing.
        // This is fire-and-forget; the handler will verify the bundle via the verifier contract.
        if (request.bundlePayload().length() > 0) {
            try {
                final var submitted = bundleSubmitter.submitBundle(request);
                logger.debug(
                        "[CLPR-SYNC-INBOUND] inbound bundle submit attempted conn={} bundleBytes={} success={}",
                        request.channelId(),
                        request.bundlePayload().length(),
                        submitted);
                if (!submitted) {
                    logger.warn(
                            "[CLPR-SYNC-INBOUND] inbound bundle submit returned false conn={} bundleBytes={}",
                            request.channelId(),
                            request.bundlePayload().length());
                }
            } catch (final Exception e) {
                logger.warn(
                        "Failed to submit inbound bundle for channel {}",
                        request.channelId().toHex(),
                        e);
            }
        }
    }

    @Override
    @NonNull
    public ClprStreamingSyncSession openStreamingSync() {
        return openStreamingSync(null);
    }

    @Override
    @NonNull
    public ClprStreamingSyncSession openStreamingSync(@Nullable final String correlationId) {
        if (!configProvider.getConfiguration().getConfigData(ClprConfig.class).enabled()) {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("CLPR is not enabled"));
        }
        return new ClprStreamingSyncSession(stateAccessor, bundleSubmitter, stateProofManager, correlationId);
    }

    @Override
    public void handleDiscovery(@NonNull final Bytes requestBytes, @NonNull final BufferedData responseBuffer) {
        requireNonNull(requestBytes);
        requireNonNull(responseBuffer);

        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            throw new StatusRuntimeException(Status.UNAVAILABLE.withDescription("CLPR is not enabled"));
        }

        // Parse the discovery request
        final ClprDiscoverEndpointsRequest request;
        try {
            request = ClprDiscoverEndpointsRequest.PROTOBUF.parse(requestBytes);
        } catch (final Exception e) {
            logger.warn("Failed to parse ClprDiscoverEndpointsRequest", e);
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("Invalid discovery request: " + e.getMessage()));
        }

        final var channelId = request.channelId();
        if (channelId.length() != 32) {
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("channel_id must be exactly 32 bytes"));
        }

        // Validate the channel exists in state
        try (final var wrappedState = stateAccessor.get()) {
            final var state = wrappedState.get();
            final var storeFactory = new ReadableStoreFactoryImpl(state);
            final var channelStore = storeFactory.readableStore(ReadableChannelStore.class);
            final var channel = channelStore.getChannel(channelId);
            if (channel == null) {
                throw new StatusRuntimeException(Status.NOT_FOUND.withDescription("Channel not found: " + channelId));
            }
        }

        // Return known endpoints from the local cache
        final var endpoints = channelManager.getKnownEndpoints(channelId);
        final var response =
                ClprDiscoverEndpointsResponse.newBuilder().endpoints(endpoints).build();

        final var responseBytes = ClprDiscoverEndpointsResponse.PROTOBUF.toBytes(response);
        responseBuffer.writeBytes(responseBytes);

        logger.debug("Handled CLPR discovery for channel {}, returned {} endpoints", channelId, endpoints.size());
    }

    /**
     * Builds the outbound {@link ClprSyncPayload} containing this node's queued messages as a
     * {@code StateProof} bundle. Returns an empty {@code bundlePayload} when no signed block
     * snapshot is available yet; the peer will skip submission and retry on its next sync tick.
     */
    @NonNull
    private ClprSyncPayload buildResponsePayload(
            @NonNull final ClprChannel channel,
            @NonNull final Bytes channelId,
            @NonNull final ClprThrottles peerThrottles,
            final long localEndpointManifestVersion,
            final long requesterObservedManifestVersion) {
        final long firstMessageId = channel.ackedMessageId() + 1;
        // Whether to embed our endpoint manifest so the requester's Step 1b refreshes its cache of
        // US. The correct signal is "the requester's observed version of OUR manifest is behind our
        // local version". We compare the requester's last-reported view of us against our local
        // version — the same axis the outbound initiator uses. This is NOT
        // channel.endpointManifestVersion(), which is the orthogonal axis (our cache of the
        // PEER's manifest) and would wrongly suppress updates when the two counters coincide. If the
        // requester is already current we skip the embed; the requester also applies any embed
        // idempotently via Step 1b, advancing only on a strictly newer version.
        final boolean peerManifestIsStale = requesterObservedManifestVersion < localEndpointManifestVersion;
        logger.debug(
                "[CLPR-SYNC-INBOUND] build response payload start conn={} firstMessageId={} "
                        + "ackedMsgId={} nextMsgId={} receivedMsgId={} peerMaxMessages={} "
                        + "peerMaxSyncBytes={} ourCacheOfPeerManifestVersion={} "
                        + "requesterObservedOfUsVersion={} localManifestVersion={} "
                        + "includeEndpointManifest={}",
                channelId,
                firstMessageId,
                channel.ackedMessageId(),
                channel.nextMessageId(),
                channel.receivedMessageId(),
                peerThrottles.maxMessagesPerBundle(),
                peerThrottles.maxSyncBytes(),
                channel.endpointManifestVersion(),
                requesterObservedManifestVersion,
                localEndpointManifestVersion,
                peerManifestIsStale);
        // Responder path: allow pure-ACK bundles so we can acknowledge an inbound message
        // even when our outbound queue has no new messages for the peer.
        final var bundlePayload = stateProofManager.buildSerializedBundleProof(
                channelId, firstMessageId, peerThrottles, true, peerManifestIsStale);

        return ClprSyncPayload.newBuilder()
                .channelId(channelId)
                .bundlePayload(bundlePayload != null ? bundlePayload : Bytes.EMPTY)
                .build();
    }
}
