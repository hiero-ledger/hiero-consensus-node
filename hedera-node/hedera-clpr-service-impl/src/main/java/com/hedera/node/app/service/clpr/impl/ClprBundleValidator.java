// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INTERNAL_STATE_CORRUPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NO_PROGRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PAYLOAD_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_RUNNING_HASH_MISMATCH;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Validates node-level bundle invariants without dispatching messages, modifying state, or charging fees.
 *
 * <p>The same checks run in two places:
 * <ul>
 *   <li>at consensus, in {@link com.hedera.node.app.service.clpr.impl.handlers.ClprSubmitBundleHandler}, against the
 *       handling state. The handler converts each failure into a {@code HandleException} and decides whether the
 *       submitting endpoint is penalized.</li>
 *   <li>before submission, in {@code ClprBundlePrevalidator}, against the node's current state. A bundle that would
 *       fail at consensus is dropped there, so the node does not pay for a {@code ClprSubmitBundle} transaction
 *       that consensus would reject.</li>
 * </ul>
 *
 * <p>Call the methods in this order:
 * <ol>
 *   <li>{@link #validatePayloadSize} on the raw wire payload, before invoking the verifier;</li>
 *   <li>{@link #validateTrustAnchor} on the verifier's attested content;</li>
 *   <li>{@link #validate} with the attested content and the channel snapshot the proof was verified against;</li>
 *   <li>{@link #validateReplies} with the result of {@link #validate} and the outbound message queue.</li>
 * </ol>
 *
 * <p>Each failure is reported as a {@link PreCheckException} with the response code consensus handling reports for
 * it. Passing before submission does not guarantee acceptance, because the channel can advance before the
 * transaction is handled.
 *
 * <p>This class is stateless and thread-safe.
 */
@Singleton
public class ClprBundleValidator {
    /** Seed for the received running hash before the first message has been received. */
    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);

    /**
     * Exists for Dagger injection; the validator holds no state.
     */
    @Inject
    public ClprBundleValidator() {}

    /**
     * Rejects an oversized wire payload before invoking a potentially expensive cryptographic verifier. The limit
     * applies to the raw bundle payload received from the peer; {@link #validate} applies the per-message limit.
     *
     * @param payload the raw bundle payload from the peer
     * @param throttles this ledger's CLPR throttles
     * @throws PreCheckException with {@code CLPR_PAYLOAD_TOO_LARGE} if the payload is longer than
     *     {@link ClprThrottles#maxSyncBytes()}
     */
    public void validatePayloadSize(@NonNull final Bytes payload, @NonNull final ClprThrottles throttles)
            throws PreCheckException {
        requireNonNull(payload);
        requireNonNull(throttles);
        validateTruePreCheck(payload.length() <= throttles.maxSyncBytes(), CLPR_PAYLOAD_TOO_LARGE);
    }

    /**
     * Checks the spec invariant that {@code new_trust_anchor_id} is non-empty if and only if {@code new_trust_anchor}
     * is non-empty.
     *
     * <p>{@link #validate} repeats this check. It is exposed separately so a caller can apply it before recording the
     * peer's observed endpoint-manifest version from the bundle metadata, which must happen even when
     * {@link #validate} later rejects the bundle with {@code CLPR_NO_PROGRESS}.
     *
     * @param content the verifier's attested bundle content
     * @throws PreCheckException with {@code CLPR_BUNDLE_VERIFICATION_FAILED} if only one of the successor trust anchor
     *     and its identifier is present
     */
    public void validateTrustAnchor(@NonNull final ClprBundleContent content) throws PreCheckException {
        requireNonNull(content);
        validateTruePreCheck(
                content.newTrustAnchor().length() == 0
                        ? content.newTrustAnchorId().length() == 0
                        : content.newTrustAnchorId().length() > 0,
                CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    /**
     * Checks the verifier's attested bundle content against the pre-bundle channel and this ledger's configuration.
     *
     * <p>The trust-anchor invariant of {@link #validateTrustAnchor} is checked first. A bundle without queue metadata
     * must then carry no messages, and must carry either a successor trust anchor or an endpoint manifest that
     * advances the channel's cached version. A bundle with queue metadata must pass these checks, in order:
     * <ol>
     *   <li><b>Progress</b> (spec §4.2 Step 1a, §2.1.2): the bundle meets at least one of the five Bundle Progress
     *       Criteria. It carries messages; installs a successor trust anchor whose id differs from the channel's
     *       current one; claims to acknowledge outbound messages past {@link ClprChannel#ackedMessageId()}; reports a
     *       peer status that advances this channel's shutdown, or this channel is {@code CLOSING} and may finish
     *       draining; or advances the endpoint manifest. A claimed acknowledgement is only matched against the
     *       outbound queue later, by {@link #validateReplies}, because the peer may legitimately defer a reply.</li>
     *   <li><b>Size limits</b> (spec §3.5.4): at most {@link ClprThrottles#maxMessagesPerBundle()} messages, and, when
     *       {@link ClprThrottles#maxMessagePayloadBytes()} is positive, no data message larger than that.</li>
     *   <li><b>Replay defense</b>: the bundle covers the peer's outbound ids that end at
     *       {@code metadata.next_message_id - 1}. Its first id must be at least 1 and at most
     *       {@code channel.received_message_id + 1}, so it skips nothing we have not received, and its last id must be
     *       at least {@code channel.received_message_id}. A bundle may repeat messages we already received, because the
     *       sender's view of our acknowledgement can lag. That prefix is dropped, which makes re-delivery idempotent;
     *       requiring an exact start would deadlock two peers whose bundles keep overlapping.</li>
     *   <li><b>Running hash</b> (spec §4.2 Step 4, §4.4): folding only the new messages into
     *       {@link ClprChannel#receivedRunningHash()} must give the peer's attested {@code sent_running_hash}. The
     *       replayed prefix is already folded into the stored hash. An empty stored hash is only valid before the
     *       first message has been received, and then starts from 32 zero bytes.</li>
     *   <li><b>Acknowledgement bounds</b>: the peer's {@code received_message_id} must not be below
     *       {@link ClprChannel#ackedMessageId()}, and, unless equal to it, must be below
     *       {@link ClprChannel#nextMessageId()}, the next id this ledger would assign.</li>
     *   <li><b>Control messages</b> (spec §1.1, §1.3): each new control message must be a known variant, and each
     *       enclosed configuration must match this ledger's protocol version and carry a valid timestamp later than
     *       the previous one. All of them are checked here, so a bad configuration rejects the bundle before any
     *       application message is dispatched.</li>
     * </ol>
     *
     * @param channel the channel before this bundle is applied; it must be the snapshot the proof was verified against
     * @param content the verifier's attested bundle content
     * @param ledgerConfig this ledger's configuration, which supplies the throttles and the protocol version
     * @return the validated content with its new (non-replayed) messages, the running hash after them, and the
     *     progress facts needed to apply it
     * @throws PreCheckException with {@code CLPR_NO_PROGRESS}, {@code CLPR_PAYLOAD_TOO_LARGE},
     *     {@code CLPR_RUNNING_HASH_MISMATCH}, or {@code CLPR_BUNDLE_VERIFICATION_FAILED} if a check fails
     */
    @NonNull
    public ValidatedBundle validate(
            @NonNull final ClprChannel channel,
            @NonNull final ClprBundleContent content,
            @NonNull final ClprLedgerConfiguration ledgerConfig)
            throws PreCheckException {
        requireNonNull(channel);
        requireNonNull(content);
        requireNonNull(ledgerConfig);
        validateTrustAnchor(content);

        final var metadata = content.metadata();
        final var messages = content.messages();
        final var newTrustAnchor = content.newTrustAnchor();
        final var newManifest = content.newEndpointManifest();
        // Compare against the original channel, before installing either state update.
        final boolean shouldUpdateManifest =
                newManifest != null && newManifest.version() > channel.endpointManifestVersion();
        if (metadata == null) {
            // A metadata-less bundle must carry a proven anchor or manifest update, with no messages.
            validateTruePreCheck(
                    messages.isEmpty() && (newTrustAnchor.length() > 0 || shouldUpdateManifest),
                    CLPR_BUNDLE_VERIFICATION_FAILED);
            return new ValidatedBundle(content, List.of(), channel.receivedRunningHash(), true, shouldUpdateManifest);
        }

        // Any one of the five progress criteria suffices. The claimed ack is rechecked by the
        // handler against the outbound queue because replies may legitimately be deferred.
        final boolean hasTrustAnchorAdvancement =
                newTrustAnchor.length() > 0 && !content.newTrustAnchorId().equals(channel.trustAnchorId());
        final boolean hasProgressOtherThanAck = !messages.isEmpty()
                || hasTrustAnchorAdvancement
                || isStateTransitionProgress(metadata.status(), channel.status())
                || shouldUpdateManifest;
        validateTruePreCheck(
                hasProgressOtherThanAck || metadata.receivedMessageId() > channel.ackedMessageId(), CLPR_NO_PROGRESS);

        final var throttles = ledgerConfig.throttlesOrThrow();
        validateTruePreCheck(messages.size() <= throttles.maxMessagesPerBundle(), CLPR_BUNDLE_VERIFICATION_FAILED);
        final long maxPayloadBytes = throttles.maxMessagePayloadBytes();
        if (maxPayloadBytes > 0) {
            for (final var payload : messages) {
                if (payload.hasMessage()) {
                    validateTruePreCheck(
                            payload.messageOrThrow().messageData().length() <= maxPayloadBytes, CLPR_PAYLOAD_TOO_LARGE);
                }
            }
        }

        // The sender may overlap messages already processed, but it must not skip any unseen
        // messages or claim to have sent fewer than we have received.
        final long lastIdBeforeBundle = metadata.nextMessageId() - messages.size() - 1;
        validateTruePreCheck(
                lastIdBeforeBundle >= 0 && lastIdBeforeBundle <= channel.receivedMessageId(),
                CLPR_BUNDLE_VERIFICATION_FAILED);
        final long skipCount = channel.receivedMessageId() - lastIdBeforeBundle;
        validateTruePreCheck(skipCount <= messages.size(), CLPR_BUNDLE_VERIFICATION_FAILED);
        final var newMessages = skipCount > 0 ? messages.subList((int) skipCount, messages.size()) : messages;

        // Resume the hash chain from our anchor, folding only the new tail.
        var computedHash = channel.receivedRunningHash();
        if (computedHash.length() == 0) {
            validateTruePreCheck(channel.receivedMessageId() == 0, CLPR_BUNDLE_VERIFICATION_FAILED);
            computedHash = ZERO_HASH;
        }
        for (final var payload : newMessages) {
            computedHash = ClprHashUtils.computeRunningHash(computedHash, payload);
        }
        validateTruePreCheck(computedHash.equals(metadata.sentRunningHash()), CLPR_RUNNING_HASH_MISMATCH);

        final var peerReceivedMessageId = metadata.receivedMessageId();
        final var oldAckedMessageId = channel.ackedMessageId();
        validateTruePreCheck(peerReceivedMessageId >= oldAckedMessageId, CLPR_BUNDLE_VERIFICATION_FAILED);
        // Preserve the initial "nothing sent, nothing acked" state, even if nextMessageId is zero.
        validateTruePreCheck(
                peerReceivedMessageId < channel.nextMessageId() || peerReceivedMessageId == oldAckedMessageId,
                CLPR_BUNDLE_VERIFICATION_FAILED);

        validateControlMessages(newMessages, ledgerConfig, channel.peerConfigTimestamp());
        return new ValidatedBundle(content, newMessages, computedHash, hasProgressOtherThanAck, shouldUpdateManifest);
    }

    /**
     * Matches the replies among the bundle's new messages against this ledger's outbound queue, without modifying
     * either.
     *
     * <p>The peer has received our outbound messages {@code channel.acked_message_id + 1} through
     * {@code metadata.received_message_id}. Walking them in order, one-way slots (control messages and our own
     * replies) need no reply, and each data slot takes the bundle's next reply, which must name that slot (spec
     * §4.5). Running out of replies is not a violation: the acknowledgement stops just before the first unanswered
     * data slot, whose reply may arrive in a later bundle (spec §5).
     *
     * <p>A reply left over after that walk must target a one-way slot that is still queued, or a slot in the range this
     * bundle acknowledges that is no longer queued. Any other leftover reply, such as one naming an unacknowledged
     * data slot or a slot outside that range that is not queued, is also an ordering violation.
     *
     * <p>A bundle without queue metadata acknowledges nothing new.
     *
     * <p>Ordering violations are returned rather than thrown: consensus handling pauses the channel and penalizes the
     * submitting endpoint while still committing that outcome, and sync admission drops the bundle instead of
     * submitting a fee-bearing transaction.
     *
     * @param channel the channel before this bundle is applied
     * @param bundle the result of {@link #validate} for the same channel and bundle
     * @param messageQueueStore this ledger's outbound message queue
     * @return the highest outbound message id the bundle acknowledges, or the first ordering violation
     * @throws PreCheckException with {@code CLPR_INTERNAL_STATE_CORRUPTION} if an outbound slot the peer has received
     *     is missing from the queue, or with {@code CLPR_NO_PROGRESS} if the bundle's only progress was a claimed
     *     acknowledgement that its replies cannot advance; consensus handling does not penalize the endpoint for
     *     either
     */
    @NonNull
    public ReplyValidation validateReplies(
            @NonNull final ClprChannel channel,
            @NonNull final ValidatedBundle bundle,
            @NonNull final ReadableMessageQueueStore messageQueueStore)
            throws PreCheckException {
        requireNonNull(channel);
        requireNonNull(bundle);
        requireNonNull(messageQueueStore);
        final var metadata = bundle.content().metadata();
        final long oldAckedMessageId = channel.ackedMessageId();
        if (metadata == null) {
            return new ReplyValidation.AckedThrough(oldAckedMessageId);
        }

        final var channelId = channel.channelId();
        final var remainingMessages = bundle.newMessages().iterator();
        final long peerReceivedMessageId = metadata.receivedMessageId();
        long newAckedMessageId = peerReceivedMessageId;
        for (long id = oldAckedMessageId + 1; id <= peerReceivedMessageId; id++) {
            final var message = messageQueueStore.getMessage(channelId, id);
            // An assigned outbound slot must exist until it has been acknowledged.
            validateTruePreCheck(message != null, CLPR_INTERNAL_STATE_CORRUPTION);
            final var payload = message.payload();
            if (payload != null && (payload.hasControl() || payload.hasMessageReply())) {
                continue;
            }
            final var reply = nextReply(remainingMessages);
            if (reply == null) {
                newAckedMessageId = id - 1;
                break;
            }
            if (reply.messageId() != id) {
                return new ReplyValidation.OrderingViolated(id, reply.messageId());
            }
        }

        // Preserve the handler's precedence: reject ineffective ack-only progress before
        // inspecting trailing replies. A peer may legitimately defer a DATA reply.
        validateTruePreCheck(
                bundle.hasProgressOtherThanAck() || newAckedMessageId > oldAckedMessageId, CLPR_NO_PROGRESS);

        while (remainingMessages.hasNext()) {
            final var payload = remainingMessages.next();
            if (!payload.hasMessageReply()) {
                continue;
            }
            final long replyTargetId = payload.messageReplyOrThrow().messageId();
            final var target = messageQueueStore.getMessage(channelId, replyTargetId);
            final var targetPayload = target == null ? null : target.payload();
            final boolean acknowledgedByThisBundle =
                    target == null && replyTargetId > oldAckedMessageId && replyTargetId <= newAckedMessageId;
            if (acknowledgedByThisBundle
                    || (targetPayload != null && (targetPayload.hasControl() || targetPayload.hasMessageReply()))) {
                continue;
            }
            return new ReplyValidation.OrderingViolated(newAckedMessageId + 1, replyTargetId);
        }
        return new ReplyValidation.AckedThrough(newAckedMessageId);
    }

    /**
     * Advances {@code messages} past the next reply and returns it, skipping messages of other kinds.
     *
     * @param messages the bundle's remaining new messages
     * @return the next reply, or {@code null} once no reply is left
     */
    @Nullable
    private static ClprMessageReply nextReply(@NonNull final Iterator<ClprMessagePayload> messages) {
        while (messages.hasNext()) {
            final var payload = messages.next();
            if (payload.hasMessageReply()) {
                return payload.messageReplyOrThrow();
            }
        }
        return null;
    }

    /**
     * Outcome of {@link #validateReplies}: either the bundle acknowledges our outbound messages through some id, or
     * one of its replies breaks the ordering, in which case it acknowledges nothing.
     */
    public sealed interface ReplyValidation {
        /**
         * The replies are in order.
         *
         * @param lastAckedMessageId the highest outbound message id the bundle acknowledges; it stops just before the
         *     first data message the bundle carries no reply for
         */
        record AckedThrough(long lastAckedMessageId) implements ReplyValidation {}

        /**
         * A reply does not name the outbound message it had to acknowledge (spec §4.5).
         *
         * @param expectedMessageId the oldest unanswered data message, which the reply had to name; for a reply left
         *     over after the acknowledged range, the first id past that range
         * @param repliedMessageId the outbound message id the reply named instead
         */
        record OrderingViolated(long expectedMessageId, long repliedMessageId) implements ReplyValidation {}
    }

    /**
     * Validates every control message among the bundle's new messages, before any of them is applied:
     * <ul>
     *   <li>Spec §1.3: a control message with no known variant set comes from a newer protocol version. Skipping it
     *       would let state diverge, so the entire bundle is rejected.</li>
     *   <li>Spec §1.1: an enclosed configuration's {@code protocol_version} must equal this ledger's.</li>
     *   <li>Spec §1.1: its {@code timestamp} must be present, with non-negative seconds and nanos in
     *       {@code [0, 999_999_999]}.</li>
     *   <li>Spec §1.3: its timestamp must be strictly later than the previous configuration's. The comparison starts
     *       from the channel's stored {@code peer_config_timestamp} and advances with each update, so several updates
     *       in one bundle must also be strictly ordered.</li>
     * </ul>
     *
     * <p>A {@code ConfigUpdate} without an enclosed configuration is accepted, and handling ignores it.
     *
     * @param messages the bundle's new (non-replayed) messages
     * @param ledgerConfig this ledger's configuration
     * @param initialPeerConfigTimestamp the channel's stored {@code peer_config_timestamp}, or {@code null} if none is
     *     stored
     * @throws PreCheckException with {@code CLPR_BUNDLE_VERIFICATION_FAILED} if a control message is invalid
     */
    private static void validateControlMessages(
            @NonNull final List<ClprMessagePayload> messages,
            @NonNull final ClprLedgerConfiguration ledgerConfig,
            @Nullable final Timestamp initialPeerConfigTimestamp)
            throws PreCheckException {
        var peerConfigTimestamp = initialPeerConfigTimestamp;
        for (final var payload : messages) {
            if (!payload.hasControl()) {
                continue;
            }
            final var control = payload.controlOrThrow();
            // Unknown control variants must reject the entire bundle (spec §1.3).
            validateTruePreCheck(control.hasConfigUpdate(), CLPR_BUNDLE_VERIFICATION_FAILED);
            final var peerConfig = control.configUpdateOrThrow().configuration();
            if (peerConfig == null) {
                continue;
            }
            validateTruePreCheck(
                    peerConfig.protocolVersion() == ledgerConfig.protocolVersion(), CLPR_BUNDLE_VERIFICATION_FAILED);
            final var peerTs = peerConfig.timestamp();
            validateTruePreCheck(
                    peerTs != null && peerTs.seconds() >= 0 && peerTs.nanos() >= 0 && peerTs.nanos() < 1_000_000_000,
                    CLPR_BUNDLE_VERIFICATION_FAILED);
            // Advance a local timestamp so multiple updates in one bundle must also be ordered.
            validateTruePreCheck(isTimestampBefore(peerConfigTimestamp, peerTs), CLPR_BUNDLE_VERIFICATION_FAILED);
            peerConfigTimestamp = peerTs;
        }
    }

    /**
     * Returns whether timestamp {@code a} is strictly before timestamp {@code b}. A {@code null} {@code a} means there
     * is no earlier timestamp, so it is before any non-null {@code b}; nothing is before a {@code null} {@code b}.
     *
     * @param a the earlier timestamp, or {@code null} if there is none
     * @param b the later timestamp, or {@code null}
     * @return {@code true} if {@code a} is strictly before {@code b}
     */
    private static boolean isTimestampBefore(@Nullable final Timestamp a, @Nullable final Timestamp b) {
        if (a == null || b == null) {
            return a == null && b != null;
        }
        if (a.seconds() != b.seconds()) {
            return a.seconds() < b.seconds();
        }
        return a.nanos() < b.nanos();
    }

    /**
     * Returns whether the peer's reported status, relative to this channel's status, is a state transition that counts
     * as bundle progress: the peer is shutting down while this channel is still {@code ACTIVE} or {@code PAUSED}; the
     * peer has drained or closed while this channel is {@code DRAINED}; or this channel is {@code CLOSING}, and the
     * bundle may complete its drain.
     *
     * @param peerStatus the peer's status from the bundle metadata
     * @param localStatus this channel's stored status
     * @return {@code true} if the transition counts as progress
     */
    private static boolean isStateTransitionProgress(
            @NonNull final ClprChannelStatus peerStatus, @NonNull final ClprChannelStatus localStatus) {
        if ((peerStatus == ClprChannelStatus.CLOSING
                        || peerStatus == ClprChannelStatus.DRAINED
                        || peerStatus == ClprChannelStatus.CLOSED)
                && (localStatus == ClprChannelStatus.ACTIVE || localStatus == ClprChannelStatus.PAUSED)) {
            return true;
        }
        if ((peerStatus == ClprChannelStatus.DRAINED || peerStatus == ClprChannelStatus.CLOSED)
                && localStatus == ClprChannelStatus.DRAINED) {
            return true;
        }
        return localStatus == ClprChannelStatus.CLOSING;
    }

    /**
     * Values computed by {@link #validate}, before any state change or application dispatch.
     *
     * @param content the verifier's attested bundle content
     * @param newMessages the bundle's messages after the replayed prefix, in order; empty for a bundle without queue
     *     metadata
     * @param receivedRunningHash the running hash after folding {@code newMessages} into the channel's received running
     *     hash; for a bundle without queue metadata, the channel's unchanged received running hash
     * @param hasProgressOtherThanAck whether the bundle meets a progress criterion other than a claimed
     *     acknowledgement; if it does not, {@link #validateReplies} requires its replies to advance the acknowledgement
     * @param shouldUpdateManifest whether the bundle's endpoint manifest advances the channel's cached version and
     *     should be stored
     */
    public record ValidatedBundle(
            @NonNull ClprBundleContent content,
            @NonNull List<ClprMessagePayload> newMessages,
            @NonNull Bytes receivedRunningHash,
            boolean hasProgressOtherThanAck,
            boolean shouldUpdateManifest) {}
}
