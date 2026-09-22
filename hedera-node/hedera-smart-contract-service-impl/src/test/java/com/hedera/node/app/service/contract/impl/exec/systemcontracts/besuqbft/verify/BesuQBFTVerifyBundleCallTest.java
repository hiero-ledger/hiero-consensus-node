// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.besuqbft.verify;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprBundleContent;
import com.hedera.node.app.service.clpr.impl.verifier.BesuQbftVerifier;
import com.hedera.node.app.service.clpr.impl.verifier.Rlp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class BesuQBFTVerifyBundleCallTest {

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
}
