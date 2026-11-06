// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.app.service.clpr.impl.verifier.ProofException;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class G1CompressionTest {

    private static final HexFormat HEX = HexFormat.of();

    /** The BLS12-381 G1 generator: the compressed form from the ZCash spec, and its coordinates. */
    private static final String GENERATOR_COMPRESSED =
            "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb";

    private static final String GENERATOR_X =
            "17f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb";
    private static final String GENERATOR_Y =
            "08b3f481e3aaa0f1a09e30ed741d8ae4fcf5e095d5d00af600db18cb2c04b3edd03cc744a2888ae40caa232946c5e7e1";

    /** {@code -G}: same x, y replaced by {@code p - y}, so the sign flag flips. */
    private static final String NEGATED_GENERATOR_COMPRESSED =
            "b7f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb";

    private static final String NEGATED_GENERATOR_Y =
            "114d1d6855d545a8aa7d76c8cf2e21f267816aef1db507c96655b9d5caac42364e6f38ba0ecb751bad54dcd6b939c2ca";

    @Nested
    @DisplayName("compress")
    class Compress {

        @Test
        @DisplayName("given the generator, then compress returns its ZCash encoding")
        void givenGenerator_thenCompressReturnsZcashEncoding() {
            assertThat(HEX.formatHex(G1Compression.compress(uncompressed(GENERATOR_X, GENERATOR_Y), "g")))
                    .isEqualTo(GENERATOR_COMPRESSED);
        }

        @Test
        @DisplayName("given the negated generator, then compress sets the sign flag")
        void givenNegatedGenerator_thenCompressSetsSignFlag() {
            assertThat(HEX.formatHex(G1Compression.compress(uncompressed(GENERATOR_X, NEGATED_GENERATOR_Y), "g")))
                    .isEqualTo(NEGATED_GENERATOR_COMPRESSED);
        }

        @Test
        @DisplayName("given 127 bytes, then compress rejects them")
        void givenShortPoint_thenCompressThrows() {
            assertThatThrownBy(() -> G1Compression.compress(new byte[127], "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key must be 128 bytes (EIP-2537 G1), got 127");
        }

        @Test
        @DisplayName("given a non-zero byte in the x padding, then compress rejects the point")
        void givenNonZeroXPadding_thenCompressThrows() {
            final byte[] point = uncompressed(GENERATOR_X, GENERATOR_Y);
            point[15] = 1;
            assertThatThrownBy(() -> G1Compression.compress(point, "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key has non-zero EIP-2537 padding");
        }

        @Test
        @DisplayName("given a non-zero byte in the y padding, then compress rejects the point")
        void givenNonZeroYPadding_thenCompressThrows() {
            final byte[] point = uncompressed(GENERATOR_X, GENERATOR_Y);
            point[64] = 1;
            assertThatThrownBy(() -> G1Compression.compress(point, "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key has non-zero EIP-2537 padding");
        }

        @Test
        @DisplayName("given an x coordinate equal to the field modulus, then compress rejects the point")
        void givenXEqualToModulus_thenCompressThrows() {
            final String modulus =
                    "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab";
            assertThatThrownBy(() -> G1Compression.compress(uncompressed(modulus, GENERATOR_Y), "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key has a coordinate that is not below the field modulus");
        }

        @Test
        @DisplayName("given the all-zero point at infinity, then compress rejects it")
        void givenPointAtInfinity_thenCompressThrows() {
            assertThatThrownBy(() -> G1Compression.compress(new byte[128], "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key is the point at infinity");
        }

        @Test
        @DisplayName(
                "given the generator's x and another y of the same sign, then compress rejects the off-curve point")
        void givenPointOffTheCurve_thenCompressThrows() {
            // Same x and same sign flag as the generator: without the curve check it would compress to G's encoding.
            final byte[] point = uncompressed(GENERATOR_X, GENERATOR_Y);
            point[127] ^= 1;
            assertThatThrownBy(() -> G1Compression.compress(point, "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key is not a point on the curve");
        }
    }

    @Nested
    @DisplayName("decompress")
    class Decompress {

        @Test
        @DisplayName("given the compressed generator, then decompress returns its coordinates")
        void givenCompressedGenerator_thenDecompressReturnsCoordinates() {
            assertThat(G1Compression.decompress(HEX.parseHex(GENERATOR_COMPRESSED), "g"))
                    .isEqualTo(uncompressed(GENERATOR_X, GENERATOR_Y));
        }

        @Test
        @DisplayName("given the compressed negated generator, then decompress picks the larger y")
        void givenCompressedNegatedGenerator_thenDecompressPicksLargerY() {
            assertThat(G1Compression.decompress(HEX.parseHex(NEGATED_GENERATOR_COMPRESSED), "g"))
                    .isEqualTo(uncompressed(GENERATOR_X, NEGATED_GENERATOR_Y));
        }

        @Test
        @DisplayName("given 47 bytes, then decompress rejects them")
        void givenShortCompressedPoint_thenDecompressThrows() {
            assertThatThrownBy(() -> G1Compression.decompress(new byte[47], "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key must be 48 bytes (compressed G1), got 47");
        }

        @Test
        @DisplayName("given a point without the compression flag, then decompress rejects it")
        void givenNoCompressionFlag_thenDecompressThrows() {
            final byte[] point = HEX.parseHex(GENERATOR_COMPRESSED);
            point[0] &= 0x7F;
            assertThatThrownBy(() -> G1Compression.decompress(point, "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key does not have the compression flag set");
        }

        @Test
        @DisplayName("given the compressed point at infinity, then decompress rejects it")
        void givenCompressedPointAtInfinity_thenDecompressThrows() {
            final byte[] point = new byte[48];
            point[0] = (byte) 0xC0;
            assertThatThrownBy(() -> G1Compression.decompress(point, "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key is the point at infinity");
        }

        @Test
        @DisplayName("given x = 1, which no curve point has, then decompress rejects it")
        void givenXOffTheCurve_thenDecompressThrows() {
            final byte[] point = new byte[48];
            point[0] = (byte) 0x80;
            point[47] = 1;
            assertThatThrownBy(() -> G1Compression.decompress(point, "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key is not the x coordinate of a point on the curve");
        }

        @Test
        @DisplayName("given an x equal to the field modulus, then decompress rejects it")
        void givenCompressedXEqualToModulus_thenDecompressThrows() {
            final byte[] point = HEX.parseHex(
                    "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab");
            point[0] |= (byte) 0x80;
            assertThatThrownBy(() -> G1Compression.decompress(point, "key"))
                    .isInstanceOf(ProofException.class)
                    .hasMessageContaining("key has a coordinate that is not below the field modulus");
        }
    }

    private static byte[] uncompressed(final String xHex, final String yHex) {
        final byte[] point = new byte[128];
        System.arraycopy(HEX.parseHex(xHex), 0, point, 16, 48);
        System.arraycopy(HEX.parseHex(yHex), 0, point, 80, 48);
        return point;
    }
}
