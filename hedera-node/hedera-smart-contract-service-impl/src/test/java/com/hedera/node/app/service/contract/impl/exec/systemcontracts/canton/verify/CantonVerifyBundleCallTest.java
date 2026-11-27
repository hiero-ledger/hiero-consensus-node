// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.canton.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprMessage;
import com.hedera.hapi.node.state.clpr.ClprMessagePayload;
import com.hedera.hapi.node.state.clpr.ClprMessageReply;
import com.hedera.hapi.node.state.clpr.ClprMessageReplyStatus;
import com.hedera.hapi.node.state.clpr.ClprMessageValue;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.ReadableMessageQueueStore;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofException;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.canton.CantonProofVerifier.OutboundLookup;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call.PricedResult;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;

class CantonVerifyBundleCallTest extends CallTestBase {
    private static final long GAS = 100_000L;
    private static final byte[] BUNDLE_PAYLOAD = {1, 2, 3};
    private static final byte[] TRUST_ANCHOR = {4, 5, 6};
    private static final byte[] CHANNEL_ID = filled(32, 0x11);
    private static final byte[] CHANNEL_CONTEXT = concat(CHANNEL_ID, filled(40, 0x5a));
    private static final byte[] SENT_HASH = filled(32, 0x02);
    private static final byte[] RECEIVED_HASH = filled(32, 0x03);
    private static final byte[] NEW_ANCHOR = {9, 9, 9};
    private static final byte[] NEW_ANCHOR_ID = {0, 0, 0, 0, 0, 0, 0, 43};
    private static final Bytes APP = Bytes.wrap("app::1220cd");
    private static final long MESSAGE_ID = 5L;

    @Mock
    private ReadableMessageQueueStore messageQueueStore;

    @Test
    void allowsStaticFrame() {
        assertThat(subject().allowsStaticFrame()).isTrue();
    }

    @Test
    void passesTheProofAnchorAndChannelContextToTheCantonVerifier() {
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenReturn(content(metadata()).build());

            subject().execute(frame);

            canton.verify(() ->
                    CantonProofVerifier.verifyBundle(eq(BUNDLE_PAYLOAD), eq(TRUST_ANCHOR), eq(CHANNEL_CONTEXT), any()));
        }
    }

    @Test
    void encodesTheVerifiedContentInTheSharedVerifierAbi() {
        final var data = dataPayload(APP);
        final var reply = replyPayload();
        final var manifest = ClprEndpointManifest.newBuilder()
                .version(4L)
                .serviceAddress(Bytes.wrap(filled(40, 0x5a)))
                .build();
        final var content = content(metadata())
                .messages(data, reply)
                .newTrustAnchor(Bytes.wrap(NEW_ANCHOR))
                .newTrustAnchorId(Bytes.wrap(NEW_ANCHOR_ID))
                .newEndpointManifest(manifest)
                .build();
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenReturn(content);

            final var result = subject().execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.COMPLETED_SUCCESS);
            assertThat(result.fullResult().gasRequirement()).isEqualTo(GAS);
            final Tuple out = ClprVerifierAbi.VERIFY_BUNDLE_RETURN.decode(
                    result.fullResult().output().toArray());
            assertThat((Tuple) out.get(0))
                    .isEqualTo(Tuple.of(
                            BigInteger.valueOf(7),
                            SENT_HASH,
                            BigInteger.valueOf(1),
                            RECEIVED_HASH,
                            ClprChannelStatus.ACTIVE.protoOrdinal(),
                            BigInteger.valueOf(2)));
            assertThat((byte[][]) out.get(1)).isDeepEqualTo(new byte[][] {
                ClprMessagePayload.PROTOBUF.toBytes(data).toByteArray(),
                ClprMessagePayload.PROTOBUF.toBytes(reply).toByteArray()
            });
            assertThat((byte[]) out.get(2)).isEqualTo(NEW_ANCHOR);
            assertThat((byte[]) out.get(3)).isEqualTo(NEW_ANCHOR_ID);
            assertThat((Tuple) out.get(4)).isEqualTo(ClprVerifierAbi.manifestStructTuple(manifest));
        }
    }

    @Test
    void encodesAbsentRotationAndManifestAsEmpty() {
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenReturn(content(metadata()).build());

            final Tuple out = ClprVerifierAbi.VERIFY_BUNDLE_RETURN.decode(
                    subject().execute(frame).fullResult().output().toArray());

            assertThat((byte[][]) out.get(1)).isEmpty();
            assertThat((byte[]) out.get(2)).isEmpty();
            assertThat((byte[]) out.get(3)).isEmpty();
            assertThat((Tuple) out.get(4)).isEqualTo(ClprVerifierAbi.manifestStructTuple(ClprEndpointManifest.DEFAULT));
        }
    }

    @Test
    void revertsWhenTheCantonVerifierRejectsTheProof() {
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenThrow(CantonProofException.replyTargetMismatch());

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void revertsOnAnUnexpectedVerifierError() {
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("boom"));

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void revertsWhenTheQueueMetadataIsNotAbiEncodable() {
        final var badMetadata = metadata()
                .copyBuilder()
                .sentRunningHash(Bytes.wrap(new byte[31]))
                .build();
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenReturn(content(badMetadata).build());

            assertFailed(subject().execute(frame));
        }
    }

    @Test
    void outboundLookupReadsTheMessageQueueThroughTheTypedAccessor() {
        final var stored = dataPayload(APP);
        given(nativeOperations.readableMessageQueueStore()).willReturn(messageQueueStore);
        given(messageQueueStore.getMessage(Bytes.wrap(CHANNEL_ID), MESSAGE_ID))
                .willReturn(ClprMessageValue.newBuilder().payload(stored).build());

        assertThat(capturedLookup().find(CHANNEL_ID, MESSAGE_ID)).isEqualTo(stored);
        // storeFactory() throws in a query context, so local prevalidation could not use it
        verify(nativeOperations, never()).storeFactory();
    }

    @Test
    void outboundLookupReturnsNullWhenTheQueueHoldsNoSuchMessage() {
        given(nativeOperations.readableMessageQueueStore()).willReturn(messageQueueStore);

        assertThat(capturedLookup().find(CHANNEL_ID, MESSAGE_ID)).isNull();
    }

    @Test
    void outboundLookupTreatsAStoredMessageWithoutPayloadAsAbsent() {
        given(nativeOperations.readableMessageQueueStore()).willReturn(messageQueueStore);
        given(messageQueueStore.getMessage(Bytes.wrap(CHANNEL_ID), MESSAGE_ID))
                .willReturn(ClprMessageValue.newBuilder().build());

        assertThat(capturedLookup().find(CHANNEL_ID, MESSAGE_ID)).isNull();
    }

    @Test
    void outboundLookupFailsAsAServiceCallFailureWhenTheQueueIsUnavailable() {
        final var cause = new IllegalStateException("gone");
        given(nativeOperations.readableMessageQueueStore()).willThrow(cause);

        final var lookup = capturedLookup();

        assertThatThrownBy(() -> lookup.find(CHANNEL_ID, MESSAGE_ID))
                .isInstanceOf(CantonProofException.class)
                .hasCause(cause)
                .satisfies(e -> assertThat(((CantonProofException) e).errorName())
                        .isEqualTo(CantonProofException.serviceCallFailed().errorName()));
    }

    @Test
    void aFailingOutboundLookupRevertsTheCall() {
        given(nativeOperations.readableMessageQueueStore()).willThrow(new IllegalStateException("gone"));
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            // As checkReplyTarget does for a REPLY: consult the outbound queue during verification
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        invocation.<OutboundLookup>getArgument(3).find(CHANNEL_ID, MESSAGE_ID);
                        return content(metadata()).build();
                    });

            assertFailed(subject().execute(frame));
        }
    }

    /** Runs the call with a stubbed verifier and returns the outbound lookup the call handed it. */
    private OutboundLookup capturedLookup() {
        final AtomicReference<OutboundLookup> lookup = new AtomicReference<>();
        try (final MockedStatic<CantonProofVerifier> canton = mockStatic(CantonProofVerifier.class)) {
            canton.when(() -> CantonProofVerifier.verifyBundle(any(), any(), any(), any()))
                    .thenAnswer(invocation -> {
                        lookup.set(invocation.getArgument(3));
                        return content(metadata()).build();
                    });
            subject().execute(frame);
        }
        return lookup.get();
    }

    private CantonVerifyBundleCall subject() {
        return new CantonVerifyBundleCall(
                mockEnhancement(), gasCalculator, BUNDLE_PAYLOAD, TRUST_ANCHOR, CHANNEL_CONTEXT);
    }

    private static ClprQueueMetadata metadata() {
        return ClprQueueMetadata.newBuilder()
                .nextMessageId(7)
                .sentRunningHash(Bytes.wrap(SENT_HASH))
                .receivedMessageId(1)
                .receivedRunningHash(Bytes.wrap(RECEIVED_HASH))
                .status(ClprChannelStatus.ACTIVE)
                .endpointManifestVersion(2)
                .build();
    }

    private static ClprBundleContent.Builder content(final ClprQueueMetadata metadata) {
        return ClprBundleContent.newBuilder().metadata(metadata);
    }

    private static ClprMessagePayload dataPayload(final Bytes target) {
        return ClprMessagePayload.newBuilder()
                .message(ClprMessage.newBuilder()
                        .connectorId(Bytes.wrap(new byte[32]))
                        .targetApplication(target)
                        .sender(Bytes.wrap(new byte[20]))
                        .messageData(Bytes.wrap(new byte[] {1})))
                .build();
    }

    private static ClprMessagePayload replyPayload() {
        return ClprMessagePayload.newBuilder()
                .messageReply(ClprMessageReply.newBuilder()
                        .messageId(MESSAGE_ID)
                        .status(ClprMessageReplyStatus.SUCCESS)
                        .messageReplyData(Bytes.wrap(new byte[] {2})))
                .build();
    }

    private static void assertFailed(final PricedResult result) {
        assertThat(result.responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
        assertThat(result.fullResult().result().state()).isEqualTo(MessageFrame.State.REVERT);
        assertThat(result.fullResult().gasRequirement()).isEqualTo(GAS);
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
