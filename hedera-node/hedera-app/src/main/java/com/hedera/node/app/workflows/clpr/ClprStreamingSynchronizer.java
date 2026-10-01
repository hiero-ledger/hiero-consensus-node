// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpoint;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.workflows.clpr.ClprEndpointClient.ClprSyncException;
import com.hedera.node.app.workflows.clpr.ClprEndpointClientImpl.ClientException;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.ClprConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Client side of the streaming sync protocol: one {@code streamingSync} stream per call to {@link #synchronize}.
 *
 * <p>Example of an exchange:
 *
 * <ol>
 *   <li>Write our one-shot {@link ClprBundleRequest} and no bundle — ours can only be shaped once the peer's request
 *       is known.
 *   <li>Read the peer's request plus its bundle and submit that bundle.
 *   <li>While the producer yields a bundle shaped by the peer's request, write it, read the peer's reply, and submit to consensus any
 *       bundle the reply carries.
 *   <li>Once this side has nothing left, half-close and read until the peer closes the stream.
 * </ol>
 *
 * <p><b>The stream ends as soon as this side (the client) has no more bundles to send.</b> The peer's session (server)
 * closes the stream as soon as we half-close, even if it still had bundles queued; they wait for the next cycle.
 *
 * <p><b>No pure-ACK bundles from this side.</b> The producer is built with {@code allowPureAck=false}.
 * Our acknowledgement of the peer's messages still reaches it whenever it opens its own cycle against us,
 * where our session replies with a pure-ACK; sending one here as well would add a fee-bearing submission on the peer
 * each cycle that is usually rejected as no-progress.
 *
 * <p>Holds no mutable state of its own; the per-peer circuit breakers and reputations live in the thread-safe
 * {@link ClprPeerSelector}.
 */
@Singleton
public final class ClprStreamingSynchronizer implements ClprSynchronizer {

    private static final Logger logger = LogManager.getLogger(ClprStreamingSynchronizer.class);

    private final ConfigProvider configProvider;
    private final ClprBundleSubmitter bundleSubmitter;
    private final ClprStateProofManager stateProofManager;
    private final ClprLeafCertManager leafCertManager;
    private final ClprEndpointClientCache clientCache;
    private final ClprPeerSelector peerSelector;
    private final int maxBundlesPerCycle;

    /**
     * Creates the synchronizer with the production per-cycle bundle limit, {@link ClprBundleProducer#MAX_BUNDLE_EXCHANGES}.
     *
     * @param configProvider    source of the live {@link ClprConfig}
     * @param bundleSubmitter   submits the peer's bundles for consensus
     * @param stateProofManager builds this side's bundles
     * @param leafCertManager   supplies this node's mTLS leaf credentials
     * @param clientCache       vends the per-peer endpoint clients
     * @param peerSelector      picks the peer to dial and tracks its failures
     */
    @Inject
    public ClprStreamingSynchronizer(
            @NonNull final ConfigProvider configProvider,
            @NonNull final ClprBundleSubmitter bundleSubmitter,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final ClprLeafCertManager leafCertManager,
            @NonNull final ClprEndpointClientCache clientCache,
            @NonNull final ClprPeerSelector peerSelector) {
        this(
                configProvider,
                bundleSubmitter,
                stateProofManager,
                leafCertManager,
                clientCache,
                peerSelector,
                ClprBundleProducer.MAX_BUNDLE_EXCHANGES);
    }

    /**
     * Lets tests lift the per-cycle bundle limit to exercise the multi-bundle loop.
     */
    @VisibleForTesting
    ClprStreamingSynchronizer(
            @NonNull final ConfigProvider configProvider,
            @NonNull final ClprBundleSubmitter bundleSubmitter,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final ClprLeafCertManager leafCertManager,
            @NonNull final ClprEndpointClientCache clientCache,
            @NonNull final ClprPeerSelector peerSelector,
            final int maxBundlesPerCycle) {
        this.configProvider = requireNonNull(configProvider);
        this.bundleSubmitter = requireNonNull(bundleSubmitter);
        this.stateProofManager = requireNonNull(stateProofManager);
        this.leafCertManager = requireNonNull(leafCertManager);
        this.clientCache = requireNonNull(clientCache);
        this.peerSelector = requireNonNull(peerSelector);
        this.maxBundlesPerCycle = maxBundlesPerCycle;
    }

    @Override
    public void synchronize(
            @NonNull final ClprChannel channel,
            @NonNull final List<ClprEndpoint> providedEndpoints,
            final long localEndpointManifestVersion,
            final long peerObservedManifestVersion) {
        final var clprConfig = configProvider.getConfiguration().getConfigData(ClprConfig.class);
        if (!clprConfig.enabled()) {
            return;
        }
        final var channelId = channel.channelId().toHex();
        final var status = channel.status();
        if (status == ClprChannelStatus.PENDING || status == ClprChannelStatus.CLOSED) {
            logger.debug("[CLPR-SYNC-OUTBOUND] skipping ineligible channel conn={} status={}", channelId, status);
            return;
        }
        final var selected = peerSelector.selectEndpoint(channel, providedEndpoints);
        if (selected == null) {
            return;
        }
        final var peerId = selected.peerId();
        final var endpoint = selected.endpoint();
        if (leafCertManager.isMtlsEnabled() && endpoint.tlsCertificate().length() == 0) {
            logger.warn("Skipping sync peer {} — mTLS is enabled but no tls_certificate in ClprEndpoint", peerId);
            return;
        }
        logger.debug(
                "[CLPR-SYNC-OUTBOUND] streaming sync start conn={} peer={} status={} "
                        + "nextMsgId={} ackedMsgId={} receivedMsgId={}",
                channelId,
                peerId,
                status,
                channel.nextMessageId(),
                channel.ackedMessageId(),
                channel.receivedMessageId());

        // The deadline covers the whole exchange, not one round trip.
        final var timeout = Duration.ofSeconds(clprConfig.syncTimeoutSeconds());
        try {
            final var client = clientCache.clientFor(
                    endpoint.serviceEndpoint().ipAddress(),
                    endpoint.serviceEndpoint().port(),
                    endpoint.tlsCertificate(),
                    leafCertManager.leafCredentials());
            final boolean completed;
            try (var call = client.streamingSync(timeout)) {
                completed = new Exchange(call, channel, peerId)
                        .run(localEndpointManifestVersion, peerObservedManifestVersion);
            }
            if (completed) {
                peerSelector.recordSuccess(peerId);
            } else {
                peerSelector.recordFailure(peerId);
            }
        } catch (final ClprSyncException | ClientException e) {
            // ClprSyncException: stream failure. ClientException: mTLS channel could not be built (e.g. malformed
            // peer certificate). Both are peer-level failures for the breaker.
            peerSelector.recordFailure(peerId);
            logger.error("Streaming sync failed for channel {} via peer {}", channelId, peerId, e);
        }
    }

    /**
     * One cycle over one stream, in the three phases of the protocol: {@link #open} (the two one-shot requests),
     * {@link #sendBundles} (our bundles, each answered by the peer), and {@link #close} (half-close and drain). Holds
     * the per-stream bookkeeping — inbound message count, whether every submission was accepted — so each phase can
     * update it without threading it through parameters.
     *
     * <p>Not thread-safe; one instance is driven by the single thread that runs {@link #synchronize}.
     */
    private final class Exchange {

        private final ClprStreamingSyncCall call;
        private final ClprChannel channel;
        private final Bytes channelIdBytes;
        private final String channelId;
        private final String peerId;

        /**
         * Messages read from the peer so far, against {@link ClprStreamingSyncSession#MAX_INBOUND_MESSAGES}.
         */
        private int inboundMessages;

        /**
         * Whether every bundle the peer sent was accepted for submission.
         */
        private boolean allSubmitted = true;

        private Exchange(
                @NonNull final ClprStreamingSyncCall call,
                @NonNull final ClprChannel channel,
                @NonNull final String peerId) {
            this.call = requireNonNull(call);
            this.channel = requireNonNull(channel);
            this.channelIdBytes = channel.channelId();
            this.channelId = channelIdBytes.toHex();
            this.peerId = requireNonNull(peerId);
        }

        /**
         * Drives the whole cycle.
         *
         * @return {@code true} when the exchange ran to a clean close and every inbound bundle was accepted for
         * submission; {@code false} when the peer closed early, exceeded the inbound message limit, or a submission was
         * refused
         * @throws ClprSyncException if a write or read on the stream fails
         */
        private boolean run(final long localEndpointManifestVersion, final long peerObservedManifestVersion)
                throws ClprSyncException {
            final var openingPayload = open();
            if (openingPayload == null) {
                return false;
            }
            final var peerRequest = openingPayload.bundleRequest();
            // The peer's live report of its copy of our manifest; the cached observation is the fallback.
            final long peerManifestVersion =
                    peerRequest != null ? peerRequest.currentEndpointManifestVersion() : peerObservedManifestVersion;
            final boolean includeEndpointManifest = peerManifestVersion < localEndpointManifestVersion;
            return sendBundles(openProducer(peerRequest), includeEndpointManifest) && close();
        }

        /**
         * Messages 1 and 2: writes our one-shot request — no bundle yet, ours can only be shaped once the peer's
         * request is known — then reads the peer's request plus its bundle for us, and submits that bundle.
         *
         * @return the peer's opening message, or {@code null} when it closed the stream instead of replying
         */
        @Nullable
        private ClprStreamingSyncPayload open() throws ClprSyncException {
            call.write(ClprStreamingSyncPayload.newBuilder()
                    .channelId(channelIdBytes)
                    .bundleRequest(bundleRequestFor(channel))
                    .build());
            // read() blocks until the peer sends a message.
            final var opening = call.read();
            if (opening == null) {
                logger.warn(
                        "[CLPR-SYNC-OUTBOUND] peer closed the stream before replying conn={} peer={}",
                        channelId,
                        peerId);
                return null;
            }
            inboundMessages++;
            submitInbound(opening);
            return opening;
        }

        /**
         * Opens this side's bundle producer for the cycle, logging the range decisions it reports.
         */
        @NonNull
        private ClprBundleProducer openProducer(@Nullable final ClprBundleRequest peerRequest) {
            final var producer =
                    new ClprBundleProducer(stateProofManager, channel, peerRequest, false, maxBundlesPerCycle);
            if (producer.peerClosed()) {
                logger.debug(
                        "[CLPR-SYNC-OUTBOUND] peer reports CLOSED; skipping bundle conn={} peer={}", channelId, peerId);
            } else if (producer.overClaimFallback()) {
                logger.warn(
                        "[CLPR-SYNC-OUTBOUND] peer received_message_id higher than local next_message_id conn={} "
                                + "peer={} requestedMessageId={} nextMsgId={}; falling back to ackedMessageId+1={}",
                        channelId,
                        peerId,
                        requireNonNull(peerRequest).currentReceivedMessageId(),
                        channel.nextMessageId(),
                        producer.nextStartingMessageId());
            }
            return producer;
        }

        /**
         * Messages 3..n: writes each bundle the producer yields and reads the peer's answer to it.
         *
         * @return {@code false} when the peer closed the stream before answering, or exceeded the inbound limit
         */
        private boolean sendBundles(@NonNull final ClprBundleProducer producer, final boolean includeEndpointManifest)
                throws ClprSyncException {
            for (Bytes bundle = producer.next(includeEndpointManifest);
                    bundle != null;
                    bundle = producer.next(includeEndpointManifest)) {
                logger.debug(
                        "[CLPR-SYNC-OUTBOUND] sending bundle conn={} peer={} bundleBytes={} nextRangeStart={} "
                                + "includeEndpointManifest={}",
                        channelId,
                        peerId,
                        bundle.length(),
                        producer.nextStartingMessageId(),
                        includeEndpointManifest);
                call.write(ClprStreamingSyncPayload.newBuilder()
                        .channelId(channelIdBytes)
                        .bundleResponse(ClprBundleResponse.newBuilder()
                                .bundlePayload(bundle)
                                .build())
                        .build());
                final var reply = call.read();
                if (reply == null) {
                    logger.warn(
                            "[CLPR-SYNC-OUTBOUND] peer closed the stream before answering our bundle conn={} peer={}",
                            channelId,
                            peerId);
                    return false;
                }
                if (!receive(reply)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Nothing left on this side: half-closes, then reads until the peer closes too.
         *
         * @return whether every bundle the peer sent this cycle was accepted, or {@code false} if it exceeded the
         * inbound limit while draining
         */
        private boolean close() throws ClprSyncException {
            call.halfClose();
            for (ClprStreamingSyncPayload trailing = call.read(); trailing != null; trailing = call.read()) {
                if (!receive(trailing)) {
                    return false;
                }
            }
            logger.debug(
                    "[CLPR-SYNC-OUTBOUND] streaming sync complete conn={} peer={} inboundMessages={} allSubmitted={}",
                    channelId,
                    peerId,
                    inboundMessages,
                    allSubmitted);
            return allSubmitted;
        }

        /**
         * Counts one message from the peer and submits the bundle it carries.
         *
         * @return {@code false} when the message exceeds the inbound limit; the stream should then be abandoned
         */
        private boolean receive(@NonNull final ClprStreamingSyncPayload message) {
            inboundMessages++;
            if (inboundMessages > ClprStreamingSyncSession.MAX_INBOUND_MESSAGES) {
                logger.warn(
                        "[CLPR-SYNC-OUTBOUND] inbound message limit reached conn={} peer={} limit={}; "
                                + "abandoning the stream",
                        channelId,
                        peerId,
                        ClprStreamingSyncSession.MAX_INBOUND_MESSAGES);
                return false;
            }
            submitInbound(message);
            return true;
        }

        /**
         * Submits the bundle {@code message} carries, if any, recording whether the submitter accepted it.
         */
        private void submitInbound(@NonNull final ClprStreamingSyncPayload message) {
            final var bundleResponse = message.bundleResponse();
            if (bundleResponse == null || bundleResponse.bundlePayload().length() == 0) {
                return;
            }
            final var submitted = bundleSubmitter.submitBundle(ClprSyncPayload.newBuilder()
                    .channelId(channelIdBytes)
                    .bundlePayload(bundleResponse.bundlePayload())
                    .build());
            logger.debug(
                    "[CLPR-SYNC-OUTBOUND] peer bundle submit attempted conn={} peer={} bundleBytes={} success={}",
                    channelId,
                    peerId,
                    bundleResponse.bundlePayload().length(),
                    submitted);
            if (!submitted) {
                logger.warn("Bundle submission failed for channel {} via peer {}", channelId, peerId);
                allSubmitted = false;
            }
        }
    }

    /**
     * This side's one-shot request: its own live view of the Channel, which the peer shapes its bundle by.
     */
    @NonNull
    private static ClprBundleRequest bundleRequestFor(@NonNull final ClprChannel channel) {
        return ClprBundleRequest.newBuilder()
                .currentReceivedMessageId(channel.receivedMessageId())
                .currentStatus(channel.status())
                .currentTrustAnchorId(channel.trustAnchorId())
                .currentEndpointManifestVersion(channel.endpointManifestVersion())
                .build();
    }
}
