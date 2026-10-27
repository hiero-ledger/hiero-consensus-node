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
 * <p>Call {@link #validatePayloadSize} before proof verification, then {@link #validate} with the
 * verifier's attested content and the channel snapshot against which the proof was verified.
 * These checks can also be used before submitting a transaction once its proof has been verified
 * locally. Consensus handling remains responsible for applying a penalty on validation failure.
 */
@Singleton
public class ClprBundleValidator {
    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);

    @Inject
    public ClprBundleValidator() {}

    /** Rejects an oversized wire payload before invoking an expensive cryptographic verifier. */
    public void validatePayloadSize(@NonNull final Bytes payload, @NonNull final ClprThrottles throttles)
            throws PreCheckException {
        requireNonNull(payload);
        requireNonNull(throttles);
        validateTruePreCheck(payload.length() <= throttles.maxSyncBytes(), CLPR_PAYLOAD_TOO_LARGE);
    }

    /**
     * Checks that a successor trust anchor and its identifier are either both present or both absent.
     * Exposed separately so a caller can check this invariant before recording peer metadata.
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
     * Checks attested bundle content against the pre-bundle channel and local ledger configuration.
     * The replayed prefix is skipped after structural checks. Only the new tail contributes to the
     * running hash and control updates. All new control updates are checked before any application
     * message can be dispatched.
     *
     * @return the validated content and values needed to process its new message tail
     * @throws PreCheckException if a node-level invariant fails
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
     * Matches replies in the new bundle tail against the outbound queue without modifying either.
     * One-way messages need no reply; a missing DATA reply defers acknowledgement of that slot.
     *
     * <p>Ordering violations are returned so consensus handling can retain its pause-and-penalize
     * behavior, while sync admission can reject the bundle before submitting a fee-bearing transaction.
     * No-progress and local state-corruption failures are thrown separately because they do not
     * receive the endpoint misbehavior penalty.
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

    /** Result of matching a bundle's new replies against the outbound queue. */
    public sealed interface ReplyValidation {
        /** Highest outbound message id acknowledged by the available replies. */
        record AckedThrough(long lastAckedMessageId) implements ReplyValidation {}

        /** An inbound reply does not name the outbound slot it was expected to acknowledge. */
        record OrderingViolated(long expectedMessageId, long repliedMessageId) implements ReplyValidation {}
    }

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

    private static boolean isTimestampBefore(@Nullable final Timestamp a, @Nullable final Timestamp b) {
        if (a == null || b == null) {
            return a == null && b != null;
        }
        if (a.seconds() != b.seconds()) {
            return a.seconds() < b.seconds();
        }
        return a.nanos() < b.nanos();
    }

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

    /** Values computed during validation, before any state changes or application dispatch. */
    public record ValidatedBundle(
            @NonNull ClprBundleContent content,
            @NonNull List<ClprMessagePayload> newMessages,
            @NonNull Bytes receivedRunningHash,
            boolean hasProgressOtherThanAck,
            boolean shouldUpdateManifest) {}
}
