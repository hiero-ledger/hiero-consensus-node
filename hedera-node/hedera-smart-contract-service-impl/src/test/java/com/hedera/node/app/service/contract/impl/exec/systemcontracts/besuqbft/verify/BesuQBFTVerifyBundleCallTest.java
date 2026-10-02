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
import org.mockito.MockedConstruction;

class BesuQBFTVerifyBundleCallTest extends CallTestBase {

    // A realistic multi-node trust anchor: RLP([encodedValidatorSet, serviceAddr20, codeHash32])
    // encodedValidatorSet = BesuQbftVerifier.encodeValidatorSet([addr20])
    private static final byte[] VALIDATOR_ADDR = new byte[20];
    private static final byte[] SERVICE_ADDR = new byte[20];
    private static final byte[] CODE_HASH = new byte[32];

    static {
        VALIDATOR_ADDR[0] = 0x11;
        SERVICE_ADDR[0] = 0x22;
        CODE_HASH[0] = 0x33;
    }

    private static byte[] buildTrustAnchorRlp() {
        byte[] encodedValidatorSet = BesuQbftVerifier.encodeValidatorSet(List.of(VALIDATOR_ADDR));
        return Rlp.encodeList(List.of(
                Rlp.encodeBytes(encodedValidatorSet), Rlp.encodeBytes(SERVICE_ADDR), Rlp.encodeBytes(CODE_HASH)));
    }

    @Test
    void augmentWithNewTrustAnchor_setsFieldsAndRoundTrips() throws Exception {
        ClprBundleContent base = ClprBundleContent.newBuilder().build();
        byte[] trustAnchorRlp = buildTrustAnchorRlp();
        byte[] anchorId = new byte[] {0x01};

        byte[] result = BesuQBFTVerifyBundleCall.augmentWithNewTrustAnchor(base, trustAnchorRlp, anchorId);

        ClprBundleContent reparsed =
                ClprBundleContent.PROTOBUF.parse(Bytes.wrap(result).toReadableSequentialData());
        assertThat(reparsed.newTrustAnchor().toByteArray()).isEqualTo(trustAnchorRlp);
        assertThat(reparsed.newTrustAnchorId().toByteArray()).isEqualTo(anchorId);
    }

    @Test
    void augmentWithNewTrustAnchor_preservesExistingFields() throws Exception {
        ClprBundleContent base =
                ClprBundleContent.newBuilder().messages(List.of()).build();
        byte[] trustAnchorRlp = buildTrustAnchorRlp();
        byte[] anchorId = new byte[] {0x02};

        byte[] result = BesuQBFTVerifyBundleCall.augmentWithNewTrustAnchor(base, trustAnchorRlp, anchorId);

        ClprBundleContent reparsed =
                ClprBundleContent.PROTOBUF.parse(Bytes.wrap(result).toReadableSequentialData());
        assertThat(reparsed.newTrustAnchor().toByteArray()).isEqualTo(trustAnchorRlp);
        assertThat(reparsed.newTrustAnchorId().toByteArray()).isEqualTo(anchorId);
    }

    @Test
    void augmentWithNewTrustAnchor_multiValidatorTrustAnchor_roundTrips() throws Exception {
        byte[] addr1 = new byte[20];
        byte[] addr2 = new byte[20];
        addr1[0] = 0x11;
        addr2[0] = 0x22;
        byte[] encodedValidatorSet = BesuQbftVerifier.encodeValidatorSet(List.of(addr1, addr2));
        byte[] trustAnchorRlp = Rlp.encodeList(List.of(
                Rlp.encodeBytes(encodedValidatorSet), Rlp.encodeBytes(SERVICE_ADDR), Rlp.encodeBytes(CODE_HASH)));
        byte[] anchorId = new byte[] {0x03};

        byte[] result = BesuQBFTVerifyBundleCall.augmentWithNewTrustAnchor(
                ClprBundleContent.newBuilder().build(), trustAnchorRlp, anchorId);

        ClprBundleContent reparsed =
                ClprBundleContent.PROTOBUF.parse(Bytes.wrap(result).toReadableSequentialData());
        assertThat(reparsed.newTrustAnchor().toByteArray()).isEqualTo(trustAnchorRlp);
        assertThat(reparsed.newTrustAnchorId().toByteArray()).isEqualTo(anchorId);
    }

    @Test
    void returnsProvenManifestVersionIgnoringRelayedContent() {
        // The relayed content claims an inflated endpoint-manifest version (999); the proven queue metadata says 7.
        // The returned metaTuple must carry the PROVEN value — a relay cannot inflate it to make the peer look
        // up-to-date and suppress our manifest re-pushes (spec §4.5, liveness).
        final byte[] sentHash = filled(32, 0x02);
        final byte[] recvHash = filled(32, 0x03);
        final byte[] lastHash = filled(32, 0x04);
        final var relayedContent = ClprBundleContent.newBuilder()
                .metadata(ClprQueueMetadata.newBuilder()
                        .nextMessageId(42)
                        .sentRunningHash(Bytes.wrap(sentHash))
                        .receivedMessageId(17)
                        .receivedRunningHash(Bytes.wrap(recvHash))
                        .status(ClprChannelStatus.ACTIVE)
                        .endpointManifestVersion(999L)
                        .build())
                .build();
        final var proven = new BesuQbftVerifier.QueueMetadata(
                42, sentHash, 17, recvHash, ClprChannelStatus.ACTIVE.protoOrdinal(), lastHash, 7L);
        final var verified = new BesuQbftVerifier.VerifiedBundle(
                filled(32, 0x01),
                ClprBundleContent.PROTOBUF.toBytes(relayedContent).toByteArray(),
                proven,
                new byte[0],
                new byte[0],
                new byte[0]);

        try (final MockedConstruction<BesuQbftVerifier> ignored =
                mockConstruction(BesuQbftVerifier.class, (mock, ctx) -> given(mock.verifyBundle(any(), any()))
                        .willReturn(verified))) {
            final var call = new BesuQBFTVerifyBundleCall(
                    mockEnhancement(), gasCalculator, new byte[] {1, 2, 3}, buildTrustAnchorRlp());
            final var result = call.execute(frame);

            assertThat(result.responseCode()).isEqualTo(SUCCESS);
            final Tuple metaTuple = ClprVerifierAbi.VERIFY_BUNDLE_RETURN
                    .decode(result.fullResult().output().toArray())
                    .get(0);
            assertThat(((BigInteger) metaTuple.get(0)).longValueExact()).isEqualTo(42L); // from relayed content
            assertThat(((BigInteger) metaTuple.get(5)).longValueExact()).isEqualTo(7L); // from PROVEN metadata
        }
    }

    private static byte[] filled(final int length, final int value) {
        final byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
