// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CLPR_BUNDLE_VERIFICATION_FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockConstruction;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.BesuQbftVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the proof binding of the Besu QBFT verify-bundle adapter: relayed queue metadata and
 * trust anchors that the verifier did not prove must never reach the CLPR Service.
 */
class BesuQBFTProofBindingAuditTest extends CallTestBase {
    private static final byte[] SERVICE_ADDR = new byte[20];
    private static final byte[] CHANNEL_ID = new byte[32];

    @Test
    void rejectsRelayMetadataWhenNoQueueMetadataWasProven() {
        final var relayed = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(42)
                        .receivedMessageId(17)
                        .sentRunningHash(Bytes.wrap(new byte[32]))
                        .receivedRunningHash(Bytes.wrap(new byte[32]))
                        .status(ClprChannelStatus.ACTIVE))
                .build();

        assertThat(executeWithAbsentQueueProof(relayed).responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    @Test
    void rejectsRelayTrustAnchorWithoutAnyVerifiedRotation() {
        final var relayed = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .sentRunningHash(Bytes.wrap(new byte[32]))
                        .receivedRunningHash(Bytes.wrap(new byte[32])))
                .newTrustAnchor(Bytes.wrap(fullAnchor((byte) 0x66)))
                .newTrustAnchorId(Bytes.wrap(new byte[] {99}))
                .build();

        assertThat(executeWithAbsentQueueProof(relayed).responseCode()).isEqualTo(CLPR_BUNDLE_VERIFICATION_FAILED);
    }

    private Call.PricedResult executeWithAbsentQueueProof(final ClprBundleContent relayed) {
        final var proven = new BesuQbftVerifier.VerifiedBundle(
                new byte[32],
                ClprBundleContent.PROTOBUF.toBytes(relayed).toByteArray(),
                new BesuQbftVerifier.QueueMetadata(0, new byte[32], 0, new byte[32], 0, new byte[32], 0L),
                new byte[0],
                new byte[0],
                new byte[0]);
        try (final var ignored = mockConstruction(
                BesuQbftVerifier.class,
                (mock, context) -> given(mock.verifyBundle(any(), any(), any())).willReturn(proven))) {
            final var subject = new BesuQBFTVerifyBundleCall(
                    mockEnhancement(),
                    gasCalculator,
                    new byte[] {1},
                    fullAnchor((byte) 0x11),
                    Bytes.wrap(CHANNEL_ID).append(Bytes.wrap(SERVICE_ADDR)).toByteArray());
            return subject.execute(frame);
        }
    }

    private static byte[] fullAnchor(final byte marker) {
        final byte[] validator = new byte[20];
        validator[0] = marker;
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(BesuQbftVerifier.encodeValidatorSet(List.of(validator))),
                Rlp.encodeBytes(SERVICE_ADDR),
                Rlp.encodeBytes(new byte[32])));
    }
}
