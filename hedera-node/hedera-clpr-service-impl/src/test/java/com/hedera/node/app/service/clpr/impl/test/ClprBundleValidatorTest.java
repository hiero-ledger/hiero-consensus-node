// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_INTERNAL_STATE_CORRUPTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_NO_PROGRESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_PAYLOAD_TOO_LARGE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_RUNNING_HASH_MISMATCH;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannel;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprConfigUpdate;
import com.hedera.hapi.node.state.clpr.ClprControlMessage;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator;
import com.hedera.node.app.service.clpr.impl.ClprBundleValidator.ReplyValidation;
import com.hedera.node.app.service.clpr.impl.ClprHashUtils;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ClprBundleValidatorTest {
    private static final Bytes ZERO_HASH = Bytes.wrap(new byte[32]);
    private static final Bytes ANCHOR = Bytes.wrap(new byte[] {1});
    private static final Bytes ANCHOR_ID = Bytes.wrap(new byte[] {2});
    private static final Timestamp PEER_TIMESTAMP = timestamp(10, 0);
    private static final ClprThrottles THROTTLES = ClprThrottles.newBuilder()
            .maxSyncBytes(1024)
            .maxMessagesPerBundle(10)
            .maxMessagePayloadBytes(3)
            .build();
    private static final ClprLedgerConfiguration LEDGER_CONFIG = ClprLedgerConfiguration.newBuilder()
            .protocolVersion(1)
            .throttles(THROTTLES)
            .build();
    private static final ClprChannel CHANNEL = ClprChannel.newBuilder()
            .status(ClprChannelStatus.ACTIVE)
            .nextMessageId(1)
            .receivedRunningHash(ZERO_HASH)
            .peerConfigTimestamp(PEER_TIMESTAMP)
            .endpointManifestVersion(2)
            .trustAnchorId(ANCHOR_ID)
            .build();

    private final ClprBundleValidator subject = new ClprBundleValidator();

    @ParameterizedTest
    @CsvSource({"0, 0", "0, 4", "3, 4", "4, 4"})
    void acceptsWirePayloadWithinLimit(final int payloadSize, final long limit) {
        assertThatCode(() -> subject.validatePayloadSize(
                        Bytes.wrap(new byte[payloadSize]),
                        THROTTLES.copyBuilder().maxSyncBytes(limit).build()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "5, 4"})
    void rejectsWirePayloadOverLimit(final int payloadSize, final long limit) {
        assertThatThrownBy(() -> subject.validatePayloadSize(
                        Bytes.wrap(new byte[payloadSize]),
                        THROTTLES.copyBuilder().maxSyncBytes(limit).build()))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CLPR_PAYLOAD_TOO_LARGE));
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, true"})
    void acceptsMatchingTrustAnchorAndIdPresence(final boolean hasAnchor, final boolean hasId) {
        assertThatCode(() -> subject.validateTrustAnchor(anchorBundle(hasAnchor, hasId)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"false, true", "true, false"})
    void rejectsMismatchedTrustAnchorAndIdPresence(final boolean hasAnchor, final boolean hasId) {
        final var content = anchorBundle(hasAnchor, hasId);
        assertThatThrownBy(() -> subject.validateTrustAnchor(content))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
        assertRejected(CHANNEL, content, CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void returnsValidatedNewTailAndRunningHash() throws PreCheckException {
        final var messages = List.of(dataPayload(3));
        final var content = bundle(CHANNEL, messages);

        final var validated = subject.validate(CHANNEL, content, LEDGER_CONFIG);

        assertThat(validated.content()).isSameAs(content);
        assertThat(validated.newMessages()).containsExactlyElementsOf(messages);
        assertThat(validated.receivedRunningHash())
                .isEqualTo(content.metadataOrThrow().sentRunningHash());
        assertThat(validated.hasProgressOtherThanAck()).isTrue();
        assertThat(validated.shouldUpdateManifest()).isFalse();
    }

    @Test
    void acceptsMetadataLessTrustAnchorUpdate() throws PreCheckException {
        final var validated = subject.validate(CHANNEL, anchorBundle(true, true), LEDGER_CONFIG);

        assertThat(validated.newMessages()).isEmpty();
        assertThat(validated.shouldUpdateManifest()).isFalse();
    }

    @Test
    void acceptsMetadataLessAdvancingManifest() throws PreCheckException {
        final var content =
                ClprBundleContent.newBuilder().newEndpointManifest(manifest(3)).build();

        final var validated = subject.validate(CHANNEL, content, LEDGER_CONFIG);

        assertThat(validated.newMessages()).isEmpty();
        assertThat(validated.shouldUpdateManifest()).isTrue();
    }

    @Test
    void rejectsMetadataLessBundleWithoutMeaningfulStateUpdate() {
        assertRejected(CHANNEL, ClprBundleContent.DEFAULT, CLPR_BUNDLE_VERIFICATION_FAILED);
        assertRejected(
                CHANNEL,
                ClprBundleContent.newBuilder().newEndpointManifest(manifest(2)).build(),
                CLPR_BUNDLE_VERIFICATION_FAILED);
        assertRejected(
                CHANNEL,
                anchorBundle(true, true).copyBuilder().messages(dataPayload(1)).build(),
                CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsNoProgressAndRestatedAnchorId() {
        final var content = bundle(CHANNEL, List.of());
        assertRejected(CHANNEL, content, CLPR_NO_PROGRESS);
        assertRejected(
                CHANNEL,
                content.copyBuilder()
                        .newTrustAnchor(ANCHOR)
                        .newTrustAnchorId(ANCHOR_ID)
                        .build(),
                CLPR_NO_PROGRESS);
    }

    @Test
    void acceptsAdvancingAnchorOrManifestAsOnlyProgress() throws PreCheckException {
        final var content = bundle(CHANNEL, List.of());
        final var anchorResult = subject.validate(
                CHANNEL,
                content.copyBuilder()
                        .newTrustAnchor(ANCHOR)
                        .newTrustAnchorId(Bytes.wrap(new byte[] {3}))
                        .build(),
                LEDGER_CONFIG);
        final var manifestResult = subject.validate(
                CHANNEL, content.copyBuilder().newEndpointManifest(manifest(3)).build(), LEDGER_CONFIG);

        assertThat(anchorResult.hasProgressOtherThanAck()).isTrue();
        assertThat(manifestResult.hasProgressOtherThanAck()).isTrue();
        assertThat(manifestResult.shouldUpdateManifest()).isTrue();
        assertRejected(
                CHANNEL, content.copyBuilder().newEndpointManifest(manifest(2)).build(), CLPR_NO_PROGRESS);
    }

    @Test
    void distinguishesClaimedAckProgressFromOtherProgress() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(3).build();
        final var content = withPeerAck(bundle(channel, List.of()), 1);

        final var validated = subject.validate(channel, content, LEDGER_CONFIG);

        assertThat(validated.hasProgressOtherThanAck()).isFalse();
        assertThat(validated.newMessages()).isEmpty();
    }

    @Test
    void acceptsPeerClosingAsOnlyProgress() throws PreCheckException {
        final var content = bundle(CHANNEL, List.of());
        final var closing = content.copyBuilder()
                .metadata(content.metadataOrThrow().copyBuilder().status(ClprChannelStatus.CLOSING))
                .build();

        assertThat(subject.validate(CHANNEL, closing, LEDGER_CONFIG).hasProgressOtherThanAck())
                .isTrue();
    }

    @Test
    void enforcesMessageCountBeforeTrimmingReplayPrefix() {
        final var channel = CHANNEL.copyBuilder().receivedMessageId(2).build();
        final var messages = List.of(dataPayload(1), dataPayload(1), dataPayload(1));
        final var content = bundle(channel, List.of(messages.getLast()))
                .copyBuilder()
                .messages(messages)
                .build();
        final var config = LEDGER_CONFIG
                .copyBuilder()
                .throttles(THROTTLES.copyBuilder().maxMessagesPerBundle(2))
                .build();

        assertThatThrownBy(() -> subject.validate(channel, content, config))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CLPR_BUNDLE_VERIFICATION_FAILED));
    }

    @Test
    void enforcesDataPayloadSizeButAllowsUnlimitedZeroLimit() throws PreCheckException {
        final var content = bundle(CHANNEL, List.of(dataPayload(4)));
        assertRejected(CHANNEL, content, CLPR_PAYLOAD_TOO_LARGE);
        final var config = LEDGER_CONFIG
                .copyBuilder()
                .throttles(THROTTLES.copyBuilder().maxMessagePayloadBytes(0))
                .build();

        assertThat(subject.validate(CHANNEL, content, config).newMessages()).hasSize(1);
    }

    @Test
    void dataPayloadLimitDoesNotApplyToReplyData() throws PreCheckException {
        final var reply = ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder().messageReplyData(Bytes.wrap(new byte[4])))
                .build();

        assertThat(subject.validate(CHANNEL, bundle(CHANNEL, List.of(reply)), LEDGER_CONFIG)
                        .newMessages())
                .containsExactly(reply);
    }

    @Test
    void rejectsReplayGapAndBundleBehindReceivedMessageId() {
        final var content = bundle(CHANNEL, List.of(dataPayload(1)));
        assertRejected(
                CHANNEL,
                content.copyBuilder()
                        .metadata(content.metadataOrThrow().copyBuilder().nextMessageId(3))
                        .build(),
                CLPR_BUNDLE_VERIFICATION_FAILED);
        assertRejected(CHANNEL.copyBuilder().receivedMessageId(2).build(), content, CLPR_BUNDLE_VERIFICATION_FAILED);
        assertRejected(
                CHANNEL,
                content.copyBuilder()
                        .metadata(content.metadataOrThrow().copyBuilder().nextMessageId(1))
                        .build(),
                CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void acceptsEmptyInitialRunningHashAsZero() throws PreCheckException {
        final var channel =
                CHANNEL.copyBuilder().receivedRunningHash(Bytes.EMPTY).build();
        final var content = bundle(channel, List.of(dataPayload(1)));

        assertThat(subject.validate(channel, content, LEDGER_CONFIG).receivedRunningHash())
                .isEqualTo(ClprHashUtils.computeRunningHash(ZERO_HASH, dataPayload(1)));
    }

    @Test
    void rejectsMissingRunningHashAfterInitialMessage() {
        final var channel = CHANNEL.copyBuilder()
                .receivedMessageId(1)
                .receivedRunningHash(Bytes.EMPTY)
                .build();

        assertRejected(channel, bundle(channel, List.of(dataPayload(1))), CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsRunningHashMismatch() {
        final var content = bundle(CHANNEL, List.of(dataPayload(1)));

        assertRejected(
                CHANNEL,
                content.copyBuilder()
                        .metadata(content.metadataOrThrow().copyBuilder().sentRunningHash(ZERO_HASH))
                        .build(),
                CLPR_RUNNING_HASH_MISMATCH);
    }

    @Test
    void rejectsAckRegressionAndAckForUnsentMessage() {
        final var channel =
                CHANNEL.copyBuilder().ackedMessageId(1).nextMessageId(3).build();
        final var content = bundle(channel, List.of(dataPayload(1)));

        assertRejected(channel, withPeerAck(content, 0), CLPR_BUNDLE_VERIFICATION_FAILED);
        assertRejected(channel, withPeerAck(content, 3), CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void acceptsUnchangedAckInInitialZeroNextMessageState() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(0).build();

        assertThat(subject.validate(channel, bundle(channel, List.of(dataPayload(1))), LEDGER_CONFIG)
                        .newMessages())
                .hasSize(1);
    }

    @Test
    void acceptsStrictlyAdvancingConfigurationsWithinBundle() throws PreCheckException {
        final var messages = List.of(configPayload(timestamp(11, 0)), configPayload(timestamp(11, 1)));

        assertThat(subject.validate(CHANNEL, bundle(CHANNEL, messages), LEDGER_CONFIG)
                        .newMessages())
                .containsExactlyElementsOf(messages);
    }

    @ParameterizedTest
    @CsvSource({"11, 0", "10, 999999999"})
    void rejectsNonIncreasingConfigurationWithinBundle(final long seconds, final int nanos) {
        final var messages = List.of(configPayload(timestamp(11, 0)), configPayload(timestamp(seconds, nanos)));

        assertRejected(CHANNEL, bundle(CHANNEL, messages), CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsConfigurationNotNewerThanStoredTimestamp() {
        assertRejected(
                CHANNEL, bundle(CHANNEL, List.of(configPayload(PEER_TIMESTAMP))), CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsUnsupportedPeerProtocolVersion() {
        final var peerConfig = LEDGER_CONFIG
                .copyBuilder()
                .protocolVersion(2)
                .timestamp(timestamp(11, 0))
                .build();

        assertRejected(CHANNEL, bundle(CHANNEL, List.of(configPayload(peerConfig))), CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @ParameterizedTest
    @MethodSource("invalidTimestamps")
    void rejectsInvalidPeerConfigurationTimestamp(final Timestamp timestamp) {
        final var peerConfig = LEDGER_CONFIG.copyBuilder().timestamp(timestamp).build();

        assertRejected(CHANNEL, bundle(CHANNEL, List.of(configPayload(peerConfig))), CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void replayPrefixDoesNotRevalidateOrRefoldOldConfigurations() throws PreCheckException {
        // The replayed configuration predates stored state and uses an incompatible protocol. It was already
        // processed, so only the new tail participates in configuration validation and the running hash.
        final var replay = configPayload(LEDGER_CONFIG
                .copyBuilder()
                .protocolVersion(2)
                .timestamp(timestamp(1, 0))
                .build());
        final var baseHash = ClprHashUtils.computeRunningHash(ZERO_HASH, replay);
        final var channel = CHANNEL.copyBuilder()
                .receivedMessageId(1)
                .receivedRunningHash(baseHash)
                .build();
        final var newMessage = configPayload(timestamp(11, 0));
        final var content = bundle(channel, List.of(newMessage))
                .copyBuilder()
                .messages(replay, newMessage)
                .build();

        final var validated = subject.validate(channel, content, LEDGER_CONFIG);

        assertThat(validated.newMessages()).containsExactly(newMessage);
        assertThat(validated.receivedRunningHash()).isEqualTo(ClprHashUtils.computeRunningHash(baseHash, newMessage));
    }

    @Test
    void acceptsEmptyConfigUpdateButRejectsUnknownControlVariant() throws PreCheckException {
        final var emptyConfig = ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.newBuilder().configUpdate(ClprConfigUpdate.DEFAULT))
                .build();
        final var unknownControl = ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.DEFAULT)
                .build();

        assertThat(subject.validate(CHANNEL, bundle(CHANNEL, List.of(emptyConfig)), LEDGER_CONFIG)
                        .newMessages())
                .containsExactly(emptyConfig);
        assertRejected(CHANNEL, bundle(CHANNEL, List.of(unknownControl)), CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void validatesRepliesInOrderAcrossDataAndOneWaySlots() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(5).build();
        final var messages = List.of(dataPayload(1), replyPayload(1), replyPayload(4));
        final var validated = subject.validate(channel, withPeerAck(bundle(channel, messages), 4), LEDGER_CONFIG);
        final var queue = queue(Map.of(
                1L, dataPayload(1),
                2L, configPayload(timestamp(11, 0)),
                3L, replyPayload(10),
                4L, dataPayload(1)));

        assertThat(subject.validateReplies(channel, validated, queue)).isEqualTo(new ReplyValidation.AckedThrough(4));
    }

    @Test
    void returnsOutOfOrderReplyBeforeAcknowledgingData() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(3).build();
        final var validated = subject.validate(
                channel, withPeerAck(bundle(channel, List.of(replyPayload(2), replyPayload(1))), 2), LEDGER_CONFIG);
        final var queue = queue(Map.of(1L, dataPayload(1), 2L, dataPayload(1)));

        assertThat(subject.validateReplies(channel, validated, queue))
                .isEqualTo(new ReplyValidation.OrderingViolated(1, 2));
    }

    @Test
    void defersDataAcknowledgementWhenReplyIsMissingButBundleOtherwiseProgresses() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(4).build();
        final var validated = subject.validate(
                channel, withPeerAck(bundle(channel, List.of(replyPayload(2), dataPayload(1))), 3), LEDGER_CONFIG);
        final var queue = queue(Map.of(1L, configPayload(timestamp(11, 0)), 2L, dataPayload(1), 3L, dataPayload(1)));

        assertThat(subject.validateReplies(channel, validated, queue)).isEqualTo(new ReplyValidation.AckedThrough(2));
    }

    @Test
    void rejectsAckOnlyBundleWhenDeferredReplyPreventsAnyProgress() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(2).build();
        final var validated = subject.validate(channel, withPeerAck(bundle(channel, List.of()), 1), LEDGER_CONFIG);

        assertThatThrownBy(() -> subject.validateReplies(channel, validated, queue(Map.of(1L, dataPayload(1)))))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CLPR_NO_PROGRESS));
    }

    @Test
    void acceptsAckOnlyBundleForOneWayMessages() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(3).build();
        final var validated = subject.validate(channel, withPeerAck(bundle(channel, List.of()), 2), LEDGER_CONFIG);
        final var queue = queue(Map.of(1L, configPayload(timestamp(11, 0)), 2L, replyPayload(10)));

        assertThat(subject.validateReplies(channel, validated, queue)).isEqualTo(new ReplyValidation.AckedThrough(2));
    }

    @Test
    void acceptsOptionalTrailingRepliesForOneWaySlots() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(3).build();
        final var validated =
                subject.validate(channel, bundle(channel, List.of(replyPayload(1), replyPayload(2))), LEDGER_CONFIG);
        final var queue = queue(Map.of(1L, configPayload(timestamp(11, 0)), 2L, replyPayload(10)));

        assertThat(subject.validateReplies(channel, validated, queue)).isEqualTo(new ReplyValidation.AckedThrough(0));
    }

    @Test
    void rejectsTrailingReplyForUnacknowledgedDataSlot() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(2).build();
        final var validated = subject.validate(channel, bundle(channel, List.of(replyPayload(1))), LEDGER_CONFIG);

        assertThat(subject.validateReplies(channel, validated, queue(Map.of(1L, dataPayload(1)))))
                .isEqualTo(new ReplyValidation.OrderingViolated(1, 1));
    }

    @ParameterizedTest
    @CsvSource({"0", "1", "9"})
    void rejectsTrailingRepliesForMissingOrPreviouslyAcknowledgedSlots(final long replyTarget)
            throws PreCheckException {
        final var channel =
                CHANNEL.copyBuilder().ackedMessageId(1).nextMessageId(3).build();
        final var validated =
                subject.validate(channel, bundle(channel, List.of(replyPayload(replyTarget))), LEDGER_CONFIG);

        assertThat(subject.validateReplies(channel, validated, queue(Map.of())))
                .isEqualTo(new ReplyValidation.OrderingViolated(2, replyTarget));
    }

    @Test
    void rejectsMissingAssignedOutboundSlotAsInternalCorruption() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().nextMessageId(2).build();
        final var validated = subject.validate(channel, withPeerAck(bundle(channel, List.of()), 1), LEDGER_CONFIG);

        assertThatThrownBy(() -> subject.validateReplies(channel, validated, queue(Map.of())))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(CLPR_INTERNAL_STATE_CORRUPTION));
    }

    @Test
    void replyValidationSkipsAlreadyProcessedReplayPrefix() throws PreCheckException {
        final var replay = replyPayload(1);
        final var channel = CHANNEL.copyBuilder()
                .receivedMessageId(1)
                .receivedRunningHash(ClprHashUtils.computeRunningHash(ZERO_HASH, replay))
                .ackedMessageId(1)
                .nextMessageId(3)
                .build();
        final var newReply = replyPayload(2);
        final var content = withPeerAck(bundle(channel, List.of(newReply)), 2)
                .copyBuilder()
                .messages(replay, newReply)
                .build();
        final var validated = subject.validate(channel, content, LEDGER_CONFIG);

        assertThat(subject.validateReplies(channel, validated, queue(Map.of(2L, dataPayload(1)))))
                .isEqualTo(new ReplyValidation.AckedThrough(2));
    }

    @Test
    void metadataLessBundleNeedsNoReplyValidation() throws PreCheckException {
        final var channel = CHANNEL.copyBuilder().ackedMessageId(1).build();
        final var validated = subject.validate(channel, anchorBundle(true, true), LEDGER_CONFIG);

        assertThat(subject.validateReplies(channel, validated, queue(Map.of())))
                .isEqualTo(new ReplyValidation.AckedThrough(1));
    }

    private static ReadableMessageQueueStore queue(final Map<Long, ClprMessagePayload> messages) {
        return new ReadableMessageQueueStore() {
            @Override
            public ClprMessageValue getMessage(final Bytes channelId, final long messageId) {
                final var payload = messages.get(messageId);
                return payload == null
                        ? null
                        : ClprMessageValue.newBuilder().payload(payload).build();
            }

            @Override
            public long sizeOfState() {
                return messages.size();
            }
        };
    }

    private static ClprMessagePayload replyPayload(final long messageId) {
        return ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder().messageId(messageId))
                .build();
    }

    private static Stream<Arguments> invalidTimestamps() {
        return Stream.of(
                Arguments.of((Timestamp) null),
                Arguments.of(timestamp(-1, 0)),
                Arguments.of(timestamp(11, -1)),
                Arguments.of(timestamp(11, 1_000_000_000)));
    }

    private void assertRejected(
            final ClprChannel channel, final ClprBundleContent content, final ResponseCodeEnum code) {
        assertThatThrownBy(() -> subject.validate(channel, content, LEDGER_CONFIG))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(code));
    }

    private static ClprBundleContent anchorBundle(final boolean hasAnchor, final boolean hasId) {
        return ClprBundleContent.newBuilder()
                .newTrustAnchor(hasAnchor ? ANCHOR : Bytes.EMPTY)
                .newTrustAnchorId(hasId ? ANCHOR_ID : Bytes.EMPTY)
                .build();
    }

    private static ClprBundleContent bundle(final ClprChannel channel, final List<ClprMessagePayload> newMessages) {
        var hash = channel.receivedRunningHash().length() == 0 ? ZERO_HASH : channel.receivedRunningHash();
        for (final var message : newMessages) {
            hash = ClprHashUtils.computeRunningHash(hash, message);
        }
        return ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(channel.receivedMessageId() + newMessages.size() + 1)
                        .receivedMessageId(channel.ackedMessageId())
                        .sentRunningHash(hash)
                        .status(ClprChannelStatus.ACTIVE))
                .messages(newMessages)
                .build();
    }

    private static ClprBundleContent withPeerAck(final ClprBundleContent content, final long peerAck) {
        return content.copyBuilder()
                .metadata(content.metadataOrThrow().copyBuilder().receivedMessageId(peerAck))
                .build();
    }

    private static ClprMessagePayload dataPayload(final int size) {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder().messageData(Bytes.wrap(new byte[size])))
                .build();
    }

    private static ClprMessagePayload configPayload(final Timestamp timestamp) {
        return configPayload(LEDGER_CONFIG.copyBuilder().timestamp(timestamp).build());
    }

    private static ClprMessagePayload configPayload(final ClprLedgerConfiguration configuration) {
        return ClprMessagePayload.newBuilder()
                .control(ClprControlMessage.newBuilder()
                        .configUpdate(ClprConfigUpdate.newBuilder().configuration(configuration)))
                .build();
    }

    private static ClprEndpointManifest manifest(final long version) {
        return ClprEndpointManifest.newBuilder().version(version).build();
    }

    private static Timestamp timestamp(final long seconds, final int nanos) {
        return Timestamp.newBuilder().seconds(seconds).nanos(nanos).build();
    }
}
