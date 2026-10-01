// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager.BundleProof;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Generates the next bundle for this channel, given the current position in the outbound queue. This class is shared by
 * both sides of the stream — {@link ClprStreamingSyncSession} on the server and {@link ClprStreamingSynchronizer} on
 * the client — so the range rules below live in exactly one place.
 *
 * <p>Each bundle continues where the previous one stopped: the range start is resolved once from the
 * peer's request ({@link #resolveInitialMessageId}) and then advances according to the last message id of the current
 * bundle.
 *
 * <p>Three things end the sequence: number of max bundles per cycle, the proof builder returning {@code null} (no
 * signed block snapshot yet, or nothing progress-bearing to send), and a bundle that carries no messages — a pure-ACK
 * or manifest-only bundle, which consumes nothing from the queue, so asking again would rebuild the identical bundle
 * forever. A peer that reports itself {@code CLOSED} ends it before it starts.
 *
 * <p><b>IMPORTANT</b>: the lifecycle of this class must be bound to a single synchronization cycle, as it is not
 * thread safe.
 *
 */
final class ClprBundleProducer {

    /**
     * How many bundles one side will send in one cycle (streaming call). The loop on either end handles any N; this is
     * what pins it.
     */
    static final int MAX_BUNDLE_EXCHANGES = 1;

    private final ClprStateProofManager stateProofManager;

    /**
     * This side's view of the Channel at the start of the cycle; every bundle this cycle is built against its peer
     * throttles.
     */
    private final ClprChannel channel;

    /**
     * Whether a message-less bundle may be emitted; see {@link ClprStateProofManager#buildBundleProof}.
     */
    private final boolean allowPureAck;

    private final int maxBundles;

    /**
     * Whether the peer reported itself {@code CLOSED} (Progress Criterion 4): it rejects everything, so no bundle is
     * built for it.
     */
    private final boolean peerClosed;

    /**
     * Whether the peer claimed a message this side never sent, so the range fell back to {@code acked_message_id + 1}.
     */
    private final boolean overClaimFallback;

    /**
     * Bundles yielded so far, against {@link #maxBundles}.
     */
    private int bundlesSent;

    /**
     * Where the next bundle's message range starts.
     */
    private long nextStartingMessageId;

    /**
     * Set once the builder reports it has nothing further to pack, so later turns stop asking.
     */
    private boolean queueEmptyForCycle;

    /**
     * @param channel     this side's view of the Channel at the start of the cycle; supplies the peer's throttles and
     *                    the bounds the range start is checked against
     * @param peerRequest the peer's one-shot request, or {@code null} when it never sent one
     */
    ClprBundleProducer(
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final ClprChannel channel,
            @Nullable final ClprBundleRequest peerRequest,
            final boolean allowPureAck) {
        this(stateProofManager, channel, peerRequest, allowPureAck, MAX_BUNDLE_EXCHANGES);
    }

    ClprBundleProducer(
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final ClprChannel channel,
            @Nullable final ClprBundleRequest peerRequest,
            final boolean allowPureAck,
            final int maxBundles) {
        this.stateProofManager = requireNonNull(stateProofManager);
        this.channel = requireNonNull(channel);
        this.allowPureAck = allowPureAck;
        this.maxBundles = maxBundles;
        this.peerClosed = peerRequest != null && peerRequest.currentStatus() == ClprChannelStatus.CLOSED;
        this.overClaimFallback =
                peerRequest != null && peerRequest.currentReceivedMessageId() >= channel.nextMessageId();
        this.nextStartingMessageId = resolveInitialMessageId(channel, peerRequest, overClaimFallback);
        this.queueEmptyForCycle = peerClosed;
    }

    /**
     * Resolves the first outbound message ID to include, which is the whole point of the two-phase exchange.
     *
     * <ul>
     *   <li>With a request in hand, start at the peer's live {@code current_received_message_id + 1}.
     *   <li><b>Over-claim guard</b>: if the peer claims to have received a message we never sent
     *       ({@code >= next_message_id}), fall back to {@code acked_message_id + 1}. Any lesser over-claim is
     *       indistinguishable from our own stale view of the peer and only harms the over-claiming peer, so it is
     *       deliberately not defended against.
     *   <li>With no request — the peer never sent one this cycle — fall back to {@code acked_message_id + 1}.
     * </ul>
     */
    private static long resolveInitialMessageId(
            @NonNull final ClprChannel channel,
            @Nullable final ClprBundleRequest peerRequest,
            final boolean overClaimFallback) {
        if (peerRequest == null || overClaimFallback) {
            return channel.ackedMessageId() + 1;
        }
        return peerRequest.currentReceivedMessageId() + 1;
    }

    /**
     * This side's next progress-bearing bundle, or {@code null} when there is none left this cycle.
     *
     * @param includeEndpointManifest whether to embed this ledger's manifest because the peer's copy is stale
     */
    @Nullable
    Bytes next(final boolean includeEndpointManifest) {
        if (!hasBundleToOffer()) {
            return null;
        }
        final BundleProof bundleProof = stateProofManager.buildBundleProof(
                channel.channelId(),
                nextStartingMessageId,
                channel.peerThrottlesOrThrow(),
                allowPureAck,
                includeEndpointManifest);
        if (bundleProof == null) {
            queueEmptyForCycle = true;
            return null;
        }
        bundlesSent++;
        nextStartingMessageId = bundleProof.lastMessageId() + 1;
        if (bundleProof.messageCount() == 0) {
            queueEmptyForCycle = true;
        }
        return bundleProof.payload();
    }

    /**
     * Whether it is still worth asking {@link #next} for another bundle — i.e., this side of the stream is under its
     * per-cycle bundle limit and the proof builder has not yet reported an empty outbound queue. Cheap enough to check
     * before opening state and building a Merkle proof.
     */
    boolean hasBundleToOffer() {
        return bundlesSent < maxBundles && !queueEmptyForCycle;
    }

    /**
     * Where the next bundle in this cycle would start.
     */
    long nextStartingMessageId() {
        return nextStartingMessageId;
    }

    /**
     * Whether the peer reported itself {@code CLOSED}, so this side sends it nothing this cycle.
     */
    boolean peerClosed() {
        return peerClosed;
    }

    /**
     * Whether the peer claimed a message this side never sent, so the range fell back to {@code acked_message_id + 1}.
     */
    boolean overClaimFallback() {
        return overClaimFallback;
    }
}
