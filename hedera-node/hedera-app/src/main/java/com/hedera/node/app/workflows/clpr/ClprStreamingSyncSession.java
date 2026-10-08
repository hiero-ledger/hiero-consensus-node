// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.state.clpr.ClprBundleRequest;
import com.hedera.hapi.node.state.clpr.ClprBundleResponse;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprStreamingSyncPayload;
import com.hedera.hapi.node.state.clpr.ClprSyncPayload;
import com.hedera.node.app.service.clpr.ClprService;
import com.hedera.node.app.service.clpr.ReadableChannelStore;
import com.hedera.node.app.service.clpr.impl.ClprStateProofManager;
import com.hedera.node.app.service.clpr.impl.ReadableEndpointManifestStoreImpl;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Controls the server-side of one sync cycle between two CLPR endpoints.
 * Each sync request to a server creates exactly one session instance.
 * A channel may have multiple periodic sync requests, leading to multiple sessions.
 *
 * <h2>State machine</h2>
 *
 * <p>{@link SessionState} is where the exchange rests <em>between</em> inbound messages; the arrows below are the
 * transitions, taken within a single {@link #onMessage} call. {@link #processBundleRequest} and
 * {@link #processBundle} decide which arrow is followed; the labels name what gets written back.
 *
 * <p>Mermaid source — paste into any renderer for the drawn version:
 *
 * <pre>
 * stateDiagram-v2
 *     [*] --> AWAITING_INITIAL_BUNDLE_REQUEST
 *
 *     AWAITING_INITIAL_BUNDLE_REQUEST --> WAITING_PEER : replyWithRequestAndBundle
 *     AWAITING_INITIAL_BUNDLE_REQUEST --> CLOSED : peer terminal / inbound limit
 *
 *     WAITING_PEER --> WAITING_PEER : replyWithBundle
 *     WAITING_PEER --> CLOSED : peer terminal / inbound limit
 *
 *     CLOSED --> [*]
 * </pre>
 *
 * <p><b>Terminal message.</b> A message carrying neither a {@code bundle_request} nor a {@code bundle_response} is
 * that side's terminal message — the peer telling us it has nothing progress-bearing left this cycle. The guard is on
 * <em>both</em> fields, not on {@code bundle_request} alone: a peer may legitimately open with a bundle and no request
 * ("nothing to ask for, but here is my data"), and treating that as terminal would swallow its bundle without ever
 * returning the acknowledgement that lets its {@code acked_message_id} advance.
 *
 * <p><b>Every non-terminal message gets a reply.</b> A peer that just wrote a bundle is blocked reading for our
 * answer, so {@code WAITING_PEER} state always writes back — even when it has nothing left to say, in which case the
 * reply is this side's own terminal message and is what lets the peer stop. Returning to {@code WAITING_PEER} silently
 * would leave both sides blocked in {@code read()} until the deadline expires.
 *
 * <p><b>The loop carries as many bundles as this side has.</b> {@code WAITING_PEER} asks its
 * {@link ClprBundleProducer} on every turn, and each bundle continues where the previous one stopped, so the
 * machine needs no changes to drain a backlog across one stream. What it will actually send today is pinned to
 * {@link ClprBundleProducer#MAX_BUNDLE_EXCHANGES} = 1 by a receiver-side limitation, not by anything structural
 * here. It is possible to fine-tune this for performance reasons, in case we want to send more bundles in one cycle.
 *
 * <p>Not thread-safe. gRPC serializes the callbacks for a single call, so one stream is driven by one thread at a
 * time and the state below needs no synchronization.
 */
public class ClprStreamingSyncSession {
    private static final Logger logger = LogManager.getLogger(ClprStreamingSyncSession.class);

    /**
     * Hard stop on inbound messages answered, independent of {@link ClprBundleProducer#MAX_BUNDLE_EXCHANGES}.
     *
     * <p>Both sides answer every non-terminal message, so a peer that simply never sends its terminal message keeps
     * the stream alive until the call deadline expires. The peer chooses how many messages to write, so without a cap
     * it also chooses how long this side stays engaged. Kept separate from the bundle limit deliberately: one is
     * protocol policy, the other is an abuse guard, and collapsing them would mean a normal cycle trips the guard and
     * its warning stops meaning anything.
     */
    static final int MAX_INBOUND_MESSAGES = 64;

    /** States of the session state machine. */
    enum SessionState {
        /** Nothing received yet. The peer's opening message is where its one-shot {@code bundle_request} arrives. */
        AWAITING_INITIAL_BUNDLE_REQUEST,
        /** Our request is on the wire; whatever the peer sends next is a bundle or its terminal message. */
        WAITING_PEER,
        /** The peer went terminal, so neither side has anything further to write. */
        CLOSED
    }

    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final ClprBundleSubmitter bundleSubmitter;
    private final ClprStateProofManager stateProofManager;

    /**
     * The peer's last-reported view of <em>our</em> endpoint-manifest version, by channel id — the node-local signal
     * recorded from the peer's bundles ({@code ClprChannelManager.peerObservedManifestVersion}).
     */
    private final ToLongFunction<Bytes> peerObservedManifestVersion;

    private SessionState sessionState = SessionState.AWAITING_INITIAL_BUNDLE_REQUEST;

    /** The Channel this stream is scoped to, fixed by the first message. */
    @Nullable
    private Bytes channelId;

    /** The peer's one-shot request, taken from its opening message. Null when it never sent one. */
    @Nullable
    private ClprBundleRequest peerRequest;

    /**
     * Yields this side's bundles for the cycle; created once the peer's opening message fixes its request.
     */
    @Nullable
    private ClprBundleProducer producer;

    /** Inbound messages answered so far, against {@link #MAX_INBOUND_MESSAGES}. */
    private int inboundMessageCount;

    /**
     * Log prefix tying every line of this session back to the transport-level call that owns it; empty when no
     * correlation id was supplied.
     */
    private final String tag;

    ClprStreamingSyncSession(
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final ClprBundleSubmitter bundleSubmitter,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final ToLongFunction<Bytes> peerObservedManifestVersion) {
        this(stateAccessor, bundleSubmitter, stateProofManager, peerObservedManifestVersion, null);
    }

    ClprStreamingSyncSession(
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final ClprBundleSubmitter bundleSubmitter,
            @NonNull final ClprStateProofManager stateProofManager,
            @NonNull final ToLongFunction<Bytes> peerObservedManifestVersion,
            @Nullable final String correlationId) {
        this.stateAccessor = requireNonNull(stateAccessor);
        this.bundleSubmitter = requireNonNull(bundleSubmitter);
        this.stateProofManager = requireNonNull(stateProofManager);
        this.peerObservedManifestVersion = requireNonNull(peerObservedManifestVersion);
        this.tag = correlationId == null ? "" : correlationId + " ";
    }

    /**
     * Handles one inbound message and returns the reply to write back, or {@code null} when the exchange is over and
     * there is nothing left to say.
     *
     * @param requestBytes the raw protobuf bytes of the inbound {@code ClprStreamingSyncPayload}
     * @return the reply to send, or {@code null} to send nothing
     * @throws StatusRuntimeException if the message is malformed, or the Channel is unknown or ineligible for
     *     sync. Whether CLPR is enabled at all is settled once when the session is opened, not per message.
     */
    @Nullable
    public ClprStreamingSyncPayload onMessage(@NonNull final Bytes requestBytes) {
        requireNonNull(requestBytes);

        logger.debug("{} new streaming message received with message len={}", this.tag, requestBytes.length());
        final ClprStreamingSyncPayload message = parseAndValidate(requestBytes);

        // Independent of the current state, an inbound bundle is worth submitting whenever one arrives — including
        // the message that trips the cap below, which is well-formed and already paid for.
        submitInboundBundle(message);

        if (sessionState != SessionState.CLOSED && ++inboundMessageCount > MAX_INBOUND_MESSAGES) {
            logger.warn(
                    "{}[CLPR-STREAM-INBOUND] inbound message limit reached channel={} limit={}; closing the stream ({} -> CLOSED)",
                    tag,
                    channelId,
                    MAX_INBOUND_MESSAGES,
                    sessionState);
            sessionState = SessionState.CLOSED;
            return null;
        }

        return switch (sessionState) {
            case AWAITING_INITIAL_BUNDLE_REQUEST -> processBundleRequest(message);
            case WAITING_PEER -> processBundle(message);
            case CLOSED -> {
                logger.debug("{} session closed", tag);
                yield null;
            }
        };
    }

    /**
     * Where the next bundle in this cycle would start; exposed so tests can assert it advances past each bundle.
     */
    @VisibleForTesting
    long nextRangeStart() {
        return producer == null ? -1 : producer.nextStartingMessageId();
    }

    /** Whether the exchange is over, so the transport may close the stream. */
    public boolean isComplete() {
        return sessionState == SessionState.CLOSED;
    }

    /**
     * Transition out of {@link SessionState#AWAITING_INITIAL_BUNDLE_REQUEST}: captures the peer's one-shot request — the whole
     * reason this protocol is two-phase — and answers with our own request plus a bundle shaped by theirs.
     */
    @Nullable
    private ClprStreamingSyncPayload processBundleRequest(@NonNull final ClprStreamingSyncPayload message) {
        this.peerRequest = message.bundleRequest();
        if (isTerminal(message)) {
            // The peer opened with nothing to send and nothing to ask for. There is no cycle to run.
            logTransition(SessionState.AWAITING_INITIAL_BUNDLE_REQUEST, SessionState.CLOSED, "peer terminal");
            sessionState = SessionState.CLOSED;
            return null;
        }
        logTransition(
                SessionState.AWAITING_INITIAL_BUNDLE_REQUEST, SessionState.WAITING_PEER, "replyWithRequestAndBundle");
        sessionState = SessionState.WAITING_PEER;
        return replyWithRequestAndBundle();
    }

    /**
     * Transition out of {@link SessionState#WAITING_PEER}: the peer either delivered a bundle (already submitted
     * above) or declared itself finished.
     */
    @Nullable
    private ClprStreamingSyncPayload processBundle(@NonNull final ClprStreamingSyncPayload message) {
        if (isTerminal(message)) {
            // The peer will not read another reply, so closing the stream says everything a terminal message would.
            logTransition(SessionState.WAITING_PEER, SessionState.CLOSED, "peer terminal");
            sessionState = SessionState.CLOSED;
            return null;
        }
        logTransition(SessionState.WAITING_PEER, SessionState.WAITING_PEER, "replyWithBundle");
        return replyWithBundle();
    }

    /** Logs one edge of the state machine described in the class-level Mermaid diagram. */
    private void logTransition(
            @NonNull final SessionState from, @NonNull final SessionState to, @NonNull final String reason) {
        logger.debug("{}[CLPR-STREAM-STATE] channel={} {} -> {} ({})", tag, channelId, from, to, reason);
    }

    /** This side's opening reply: its one-shot request, plus its bundle for the cycle when one can be built. */
    @NonNull
    private ClprStreamingSyncPayload replyWithRequestAndBundle() {
        return reply(true);
    }

    /**
     * A reply once this side's request is spent: the next progress-bearing bundle if there is one, otherwise this
     * side's terminal message (both fields absent), which is what tells the peer it can stop.
     */
    @NonNull
    private ClprStreamingSyncPayload replyWithBundle() {
        return reply(false);
    }

    /**
     * Shared body of the two reply transitions. The Channel is read once per reply, so the request fields and the
     * bundle are drawn from the same immutable state.
     */
    @NonNull
    private ClprStreamingSyncPayload reply(final boolean includeOwnRequest) {
        final var channelId = requireNonNull(this.channelId);
        final var builder = ClprStreamingSyncPayload.newBuilder().channelId(channelId);
        // replies with an empty bundle if there is nothing to offer or max bundle limit reached.
        if (!includeOwnRequest && producer != null && !producer.hasBundleToOffer()) {
            return builder.build();
        }

        try (final var wrappedState = stateAccessor.get()) {
            final var localState = wrappedState.get();
            final var channel = getEligibleChannel(localState, channelId);
            if (includeOwnRequest) {
                builder.bundleRequest(ClprBundleRequest.newBuilder()
                        .currentReceivedMessageId(channel.receivedMessageId())
                        .currentStatus(channel.status())
                        .currentTrustAnchorId(channel.trustAnchorId())
                        .currentEndpointManifestVersion(channel.endpointManifestVersion())
                        .build());
                this.producer = openProducer(channel);
            }
            final var producer = requireNonNull(this.producer);
            final var bundlePayload =
                    producer.hasBundleToOffer() ? producer.next(isPeerManifestStale(localState, channel)) : null;
            if (bundlePayload != null) {
                builder.bundleResponse(ClprBundleResponse.newBuilder()
                        .bundlePayload(bundlePayload)
                        .build());
            }
        }
        return builder.build();
    }

    /**
     * Opens this side's bundle producer for the cycle, logging the range decisions it reports.
     */
    @NonNull
    private ClprBundleProducer openProducer(@NonNull final ClprChannel channel) {
        final var producer = new ClprBundleProducer(stateProofManager, channel, peerRequest, true);
        if (producer.peerClosed()) {
            logger.debug("{}[CLPR-STREAM-INBOUND] peer reports CLOSED; skipping bundle channel={}", tag, channelId);
        } else if (producer.overClaimFallback()) {
            logger.warn(
                    "{}[CLPR-STREAM-INBOUND] peer received_message_id higher than local next_message_id channel={} "
                            + "requestedMessageId={} nextMsgId={}; falling back to ackedMessageId+1={}",
                    tag,
                    channelId,
                    requireNonNull(peerRequest).currentReceivedMessageId(),
                    channel.nextMessageId(),
                    producer.nextStartingMessageId());
        }
        return producer;
    }

    private static boolean isTerminal(@NonNull final ClprStreamingSyncPayload message) {
        return message.bundleRequest() == null && message.bundleResponse() == null;
    }

    /**
     * Rejects the call unless the message is a well-formed payload for this stream's Channel, then returns the
     * parsed message. The internal channel id is also extracted from the first BundleRequest.
     */
    @NonNull
    private ClprStreamingSyncPayload parseAndValidate(@NonNull final Bytes requestBytes) {
        final ClprStreamingSyncPayload message;
        try {
            message = ClprStreamingSyncPayload.PROTOBUF.parseStrict(requestBytes);
        } catch (final Exception e) {
            logger.warn("{}Failed to parse ClprStreamingSyncPayload", tag, e);
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("Invalid ClprStreamingSyncPayload: " + e.getMessage()));
        }

        final var messageChannelId = message.channelId();
        if (messageChannelId.length() != 32) {
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("channel_id must be exactly 32 bytes"));
        }
        if (channelId == null) {
            channelId = messageChannelId;
        } else if (!channelId.equals(messageChannelId)) {
            throw new StatusRuntimeException(
                    Status.INVALID_ARGUMENT.withDescription("channel_id changed mid-stream: expected "
                            + channelId.toHex() + " but got " + messageChannelId.toHex()));
        }

        logger.debug(
                "{}[CLPR-STREAM-INBOUND] message received channel={} state={} hasRequest={} bundleBytes={}",
                tag,
                channelId,
                sessionState,
                message.bundleRequest() != null,
                message.bundleResponse() == null
                        ? 0
                        : message.bundleResponse().bundlePayload().length());
        return message;
    }

    /**
     * Submits an inbound bundle as a {@code ClprSubmitBundle} transaction for consensus processing.
     * This call is fire-and-forget. Failures are logged and swallowed: the peer's bundle reaching
     * consensus is independent of this stream making progress.
     */
    private void submitInboundBundle(@NonNull final ClprStreamingSyncPayload message) {
        final var bundleResponse = message.bundleResponse();
        if (bundleResponse == null || bundleResponse.bundlePayload().length() == 0) {
            return;
        }
        final Bytes channelId = requireNonNull(this.channelId);
        final var payload = ClprSyncPayload.newBuilder()
                .channelId(channelId)
                .bundlePayload(bundleResponse.bundlePayload())
                .build();
        try {
            final var submitted = bundleSubmitter.submitBundle(payload);
            logger.debug(
                    "{}[CLPR-STREAM-INBOUND] inbound bundle submit attempted channel={} bundleBytes={} success={}",
                    tag,
                    this.channelId,
                    payload.bundlePayload().length(),
                    submitted);
            if (!submitted) {
                logger.warn(
                        "{}[CLPR-STREAM-INBOUND] inbound bundle submit returned false channel={} bundleBytes={}",
                        tag,
                        this.channelId,
                        payload.bundlePayload().length());
            }
        } catch (final RuntimeException e) {
            // Not reported to the peer: failing the call would also discard this side's own bundle and its ACK of
            // what the peer sent. The peer learns of the failure anyway — our current_received_message_id does not
            // advance, so its next cycle resends the same range.
            logger.error("{}Failed to submit inbound bundle for channel {}", tag, channelId.toHex(), e);
        }
    }

    /**
     * Reads the Channel and rejects the stream if it is unknown or in a state that cannot sync — the same guard
     * the unary responder applies.
     */
    @NonNull
    private ClprChannel getEligibleChannel(@NonNull final State localState, @NonNull final Bytes channelId) {
        final var channelStore = new ReadableStoreFactoryImpl(localState).readableStore(ReadableChannelStore.class);
        final var channel = channelStore.getChannel(channelId);
        if (channel == null) {
            throw new StatusRuntimeException(Status.NOT_FOUND.withDescription("Channel not found: " + channelId));
        }
        if (channel.status() == ClprChannelStatus.CLOSED || channel.status() == ClprChannelStatus.PENDING) {
            throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(
                    "Channel is not eligible for sync, status=" + channel.status()));
        }
        return channel;
    }

    private boolean isPeerManifestStale(State localState, ClprChannel channel) {
        final long localManifestVersion = new ReadableEndpointManifestStoreImpl(
                        localState.getReadableStates(ClprService.NAME))
                .get()
                .version();
        // The peer's view of OUR manifest: its request states it directly; without one, fall back to the version it
        // last reported in a bundle, as the unary responder does. channel.endpointManifestVersion() is the other axis
        // (our cached version of the PEER's manifest) and must not stand in for it.
        final long peerManifestVersion = peerRequest != null
                ? peerRequest.currentEndpointManifestVersion()
                : peerObservedManifestVersion.applyAsLong(channel.channelId());
        return peerManifestVersion < localManifestVersion;
    }
}
