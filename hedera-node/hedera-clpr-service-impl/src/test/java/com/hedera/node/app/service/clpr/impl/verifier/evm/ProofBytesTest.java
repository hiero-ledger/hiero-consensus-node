// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.evm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ProofBytesTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void keccak256_matchesKnownEmptyInputDigest() {
        // keccak256("") is a well-known constant.
        assertThat(HEX.formatHex(ProofBytes.keccak256(new byte[0])))
                .isEqualTo("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
    }

    @Test
    void checkedCopy_exactLength_returnsDefensiveCopy() {
        byte[] input = {1, 2, 3};
        byte[] out = ProofBytes.checkedCopy(input, 3, "x");
        assertThat(out).isEqualTo(input).isNotSameAs(input);
    }

    @Test
    void checkedCopy_wrongLength_throwsIllegalArgument() {
        assertThatThrownBy(() -> ProofBytes.checkedCopy(new byte[2], 3, "field"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field must be 3 bytes, got 2");
    }

    @Test
    void requireLength_exactLength_doesNotThrow() {
        ProofBytes.requireLength(new byte[] {1, 2, 3}, 3, "x");
    }

    @Test
    void requireLength_wrongLength_throwsIllegalArgument() {
        assertThatThrownBy(() -> ProofBytes.requireLength(new byte[2], 3, "field"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field must be 3 bytes, got 2");
    }

    @Test
    void toNibbles_splitsEachByteIntoHighAndLowNibble() {
        // 0xAB → high=0xA, low=0xB ; 0x0F → high=0x0, low=0xF
        assertThat(ProofBytes.toNibbles(new byte[] {(byte) 0xAB, (byte) 0x0F}))
                .isEqualTo(new int[] {0xA, 0xB, 0x0, 0xF});
    }

    @Test
    void toNibbles_emptyInput_returnsEmptyArray() {
        assertThat(ProofBytes.toNibbles(new byte[0])).isEmpty();
    }

    @Test
    void leftPad32_padsLeadingZeros() {
        byte[] out = ProofBytes.leftPad32(new byte[] {(byte) 0xAB, (byte) 0xCD}, "x");
        assertThat(out).hasSize(32);
        assertThat(out[30]).isEqualTo((byte) 0xAB);
        assertThat(out[31]).isEqualTo((byte) 0xCD);
        for (int i = 0; i < 30; i++) {
            assertThat(out[i]).isZero();
        }
    }

    @Test
    void leftPad32_exactly32Bytes_isUnchanged() {
        byte[] input = new byte[32];
        for (int i = 0; i < 32; i++) {
            input[i] = (byte) (i + 1);
        }
        assertThat(ProofBytes.leftPad32(input, "x")).isEqualTo(input);
    }

    @Test
    void leftPad32_longerThan32Bytes_throwsProofException() {
        assertThatThrownBy(() -> ProofBytes.leftPad32(new byte[33], "x"))
                .isInstanceOf(ProofException.class)
                .hasMessageContaining("longer than 32 bytes");
    }
}
