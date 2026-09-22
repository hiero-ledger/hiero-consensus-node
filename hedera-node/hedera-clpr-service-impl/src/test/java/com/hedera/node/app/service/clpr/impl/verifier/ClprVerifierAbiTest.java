// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.esaulpaugh.headlong.abi.Tuple;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for the shared metadata-absent sentinel on {@link ClprVerifierAbi}. */
class ClprVerifierAbiTest {

    @Test
    @DisplayName("absentMetadataTuple is the zero sentinel with full 32-byte hashes, recognised as absent")
    void absentMetadataTupleIsRecognisedAsAbsent() {
        final Tuple absent = ClprVerifierAbi.absentMetadataTuple();
        assertThat((BigInteger) absent.get(0)).isEqualTo(BigInteger.ZERO);
        // Full 32-byte zero arrays (not empty) so the sentinel encodes against the bytes32 members.
        assertThat((byte[]) absent.get(1)).hasSize(32);
        assertThat((byte[]) absent.get(3)).hasSize(32);
        // Round-trips through the return tuple's encoder.
        assertThat(ClprVerifierAbi.VERIFY_BUNDLE_V3_RETURN.encode(Tuple.of(
                        absent,
                        new byte[0][],
                        new byte[0],
                        new byte[0],
                        ClprVerifierAbi.manifestStructTuple(
                                com.hedera.hapi.node.state.clpr.ClprEndpointManifest.DEFAULT))))
                .isNotNull();
        assertThat(ClprVerifierAbi.isMetadataAbsent(absent)).isTrue();
    }

    @Test
    @DisplayName("isMetadataAbsent keys only on nextMessageId — any non-zero value is a real bundle")
    void isMetadataAbsentKeysOnNextMessageId() {
        final Tuple present = Tuple.of(BigInteger.ONE, new byte[32], BigInteger.ZERO, new byte[32], 0);
        assertThat(ClprVerifierAbi.isMetadataAbsent(present)).isFalse();
        // The other members being zero does NOT make it absent — only nextMessageId is the discriminator.
        final Tuple genesisReal = Tuple.of(BigInteger.valueOf(5L), new byte[32], BigInteger.ZERO, new byte[32], 0);
        assertThat(ClprVerifierAbi.isMetadataAbsent(genesisReal)).isFalse();
    }
}
