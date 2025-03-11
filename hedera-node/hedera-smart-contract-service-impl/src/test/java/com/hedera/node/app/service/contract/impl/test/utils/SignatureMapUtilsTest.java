// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static com.hedera.node.app.service.contract.impl.utils.SignatureMapUtils.preprocessEcdsaSignatures;
import static com.hedera.node.app.service.contract.impl.utils.SignatureMapUtils.validChainId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.SignaturePair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the SignatureMapUtils class.
 */
class SignatureMapUtilsTest {

    @Test
    void testPreprocessEcdsaSignatures() {
        final var keyBytes = Bytes.wrap(new byte[65]);
        final var sigMap = new SignatureMap.Builder()
                .sigPair(new SignaturePair.Builder().ecdsaSecp256k1(keyBytes).build())
                .build();
        final var resultSigMap = preprocessEcdsaSignatures(sigMap, 0);
        assertThat(resultSigMap.sigPair().get(0).hasEcdsaSecp256k1()).isTrue();
        // Assert truncated to 64 bytes
        assertThat(resultSigMap.sigPair().get(0).ecdsaSecp256k1().length()).isEqualTo(64);
    }

    @Test
    void testPreprocessEcdsaSignaturesForEdSigs() {
        final var keyBytes = Bytes.wrap(new byte[65]);
        final var sigMap = new SignatureMap.Builder()
                .sigPair(new SignaturePair.Builder().ed25519(keyBytes).build())
                .build();
        final var resultSigMap = preprocessEcdsaSignatures(sigMap, 0);
        assertThat(resultSigMap.sigPair().get(0).hasEd25519()).isTrue();
        // Assert not truncated
        assertThat(resultSigMap.sigPair().get(0).ed25519().length()).isEqualTo(65);
    }

    @Test
    void testPreprocessEcdsaSignaturesWrongChainId() {
        final var chainId = 199;
        final var ecSig = new byte[65];
        ecSig[64] = (byte) (35 + (chainId * 3));
        final var keyBytes = Bytes.wrap(ecSig);
        final var sigMap = new SignatureMap.Builder()
                .sigPair(new SignaturePair.Builder().ecdsaSecp256k1(keyBytes).build())
                .build();
        assertThrows(IllegalArgumentException.class, () -> preprocessEcdsaSignatures(sigMap, 0));
    }

    @Test
    void testWrongChainId() {
        final var chainId = 199;
        final var ecSig = new byte[65];
        ecSig[64] = (byte) (35 + (chainId * 3));
        assertThat(validChainId(ecSig, chainId)).isFalse();
    }

    @Test
    void testCorrectChainId() {
        final var chainId = 199;
        int v = 35 + (chainId * 2);
        // distribute the v value across the last two bytes
        final var ecSig = new byte[66];
        ecSig[65] = (byte) (v & 0xFF);
        v >>= 8;
        ecSig[64] = (byte) (v & 0xFF);
        assertThat(validChainId(ecSig, chainId)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void testOutrageouslyLargeChainId(final int parity) {
        final var kakarotStarknetSepoliaChainid = 0x34550b76e4065L; // as seen on chainlist.org
        var v = 35 + parity + (kakarotStarknetSepoliaChainid * 2);
        var vBytes = BigInteger.valueOf(v).toByteArray();

        var ecSig = new byte[64 + vBytes.length];
        System.arraycopy(vBytes, 0, ecSig, 64, vBytes.length);

        assertThat(validChainId(ecSig, kakarotStarknetSepoliaChainid)).isTrue();
        assertThat(validChainId(ecSig, kakarotStarknetSepoliaChainid - 1)).isFalse();
    }

    @Test
    void testOutOfBoundsChainId() {
        var vBytes = new BigInteger("1122334455667788AABB", 16).toByteArray();

        var ecSig = new byte[64 + vBytes.length];
        System.arraycopy(vBytes, 0, ecSig, 64, vBytes.length);

        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> validChainId(ecSig, 123456L));
    }

    @Test
    void testChainIdBelow35() {
        final var ecSig = new byte[65];
        ecSig[64] = 34;
        assertThat(validChainId(ecSig, 0)).isTrue();
    }

    @Test
    void testSignatureTooShort() {
        final var ecSig = new byte[64];
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> validChainId(ecSig, 0));
    }
}
