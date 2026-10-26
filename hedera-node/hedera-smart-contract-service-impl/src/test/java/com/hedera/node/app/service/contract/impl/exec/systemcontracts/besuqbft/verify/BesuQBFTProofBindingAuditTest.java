// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockConstruction;

import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.hapi.node.state.clpr.ClprChannelStatus;
import com.hedera.hapi.node.state.clpr.ClprQueueMetadata;
import com.hedera.node.app.service.clpr.impl.verifier.BesuQbftVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.ClprVerifierAbi;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Characterizes unsafe adapter behavior; these assertions must be inverted when the bugs are fixed. */
class BesuQBFTProofBindingAuditTest extends CallTestBase {
    @Test
    void auditReturnsRelayMetadataWhenNoQueueMetadataWasProven() {
        final var relayed = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(42)
                        .receivedMessageId(17)
                        .sentRunningHash(Bytes.wrap(new byte[32]))
                        .receivedRunningHash(Bytes.wrap(new byte[32]))
                        .status(ClprChannelStatus.ACTIVE))
                .build();
        final Tuple output = executeWithAbsentQueueProof(relayed);
        final Tuple metadata = output.get(0);
        assertThat((BigInteger) metadata.get(0)).isEqualTo(BigInteger.valueOf(42));
        assertThat((BigInteger) metadata.get(2)).isEqualTo(BigInteger.valueOf(17));
    }

    @Test
    void auditReturnsRelayTrustAnchorWithoutAnyVerifiedRotation() {
        final var attackerAnchor = fullAnchor((byte) 0x66);
        final var relayed = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .sentRunningHash(Bytes.wrap(new byte[32]))
                        .receivedRunningHash(Bytes.wrap(new byte[32])))
                .newTrustAnchor(Bytes.wrap(attackerAnchor))
                .newTrustAnchorId(Bytes.wrap(new byte[] {99}))
                .build();
        final Tuple output = executeWithAbsentQueueProof(relayed);
        assertThat((byte[]) output.get(2)).isEqualTo(attackerAnchor);
        assertThat((byte[]) output.get(3)).containsExactly((byte) 99);
        // nextMessageId=0 is the adapter's absent-metadata sentinel: a state-update-only bundle.
        assertThat((BigInteger) ((Tuple) output.get(0)).get(0)).isZero();
    }

    private Tuple executeWithAbsentQueueProof(final ClprBundleContent relayed) {
        final var proven = new BesuQbftVerifier.VerifiedBundle(
                new byte[32],
                ClprBundleContent.PROTOBUF.toBytes(relayed).toByteArray(),
                new BesuQbftVerifier.QueueMetadata(0, new byte[32], 0, new byte[32], 0, new byte[32], 0L),
                new byte[0],
                new byte[0],
                new byte[0]);
        try (final var ignored =
                mockConstruction(BesuQbftVerifier.class, (mock, context) -> given(mock.verifyBundle(any(), any()))
                        .willReturn(proven))) {
            final var subject = new BesuQBFTVerifyBundleCall(
                    mockEnhancement(), gasCalculator, new byte[] {1}, fullAnchor((byte) 0x11));
            final var result = subject.execute(frame);
            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            return ClprVerifierAbi.VERIFY_BUNDLE_RETURN.decode(
                    result.fullResult().output().toArray());
        }
    }

    private static byte[] fullAnchor(final byte marker) {
        final byte[] validator = new byte[20];
        validator[0] = marker;
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(BesuQbftVerifier.encodeValidatorSet(List.of(validator))),
                Rlp.encodeBytes(new byte[20]),
                Rlp.encodeBytes(new byte[32])));
    }
}
