// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.verifier.ethereum;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * Converts BLS12-381 G1 points between the EIP-2537 uncompressed encoding and the ZCash compressed encoding.
 *
 * <p>Bundles carry some G1 points uncompressed ({@code pad16 ‖ x ‖ pad16 ‖ y}, 128 bytes, each coordinate a 48-byte
 * big-endian field element), while the beacon chain commits to the 48-byte compressed form ({@code x} with flag bits
 * in its top three bits). Compressing keeps {@code x} and the sign of {@code y}. Decompressing recovers {@code y} from
 * the curve equation {@code y² = x³ + 4}, and is used only for the keys a config payload carries compressed.
 *
 * <p>Both directions check that the point is on the curve. The compressed form identifies a point only among the points
 * of the curve: without the check, an off-curve {@code (x, y')} whose {@code y'} lies on the same side of
 * {@code (p - 1) / 2} as a committed key's {@code y} would compress to that key, and pass a membership proof against a
 * beacon-committed root. Neither direction checks that the point is in the prime-order subgroup; the BLS library
 * checks that.
 */
final class G1Compression {

    static final int COMPRESSED_LENGTH = 48;
    static final int UNCOMPRESSED_LENGTH = 128;

    private static final int PADDING_LENGTH = 16;
    private static final int FIELD_ELEMENT_LENGTH = 48;
    private static final int X_OFFSET = PADDING_LENGTH;
    private static final int Y_PADDING_OFFSET = PADDING_LENGTH + FIELD_ELEMENT_LENGTH;
    private static final int Y_OFFSET = Y_PADDING_OFFSET + PADDING_LENGTH;

    /** The BLS12-381 base field modulus. */
    private static final BigInteger FIELD_MODULUS = new BigInteger(
            "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    /** {@code (p - 1) / 2}: a {@code y} above it is the lexicographically largest root, flagged by the sign bit. */
    private static final BigInteger HALF_FIELD_MODULUS = FIELD_MODULUS.shiftRight(1);

    private static final int COMPRESSION_FLAG = 0x80;
    private static final int INFINITY_FLAG = 0x40;
    private static final int SIGN_FLAG = 0x20;

    /** The three flag bits at the top of the first byte of a compressed point. */
    private static final int FLAG_BITS = COMPRESSION_FLAG | INFINITY_FLAG | SIGN_FLAG;

    /** The constant {@code b} of the BLS12-381 G1 curve {@code y² = x³ + b}. */
    private static final BigInteger CURVE_B = BigInteger.valueOf(4);

    /** {@code (p + 1) / 4}: as {@code p ≡ 3 (mod 4)}, {@code a^((p+1)/4)} is a square root of any square {@code a}. */
    private static final BigInteger SQUARE_ROOT_EXPONENT =
            FIELD_MODULUS.add(BigInteger.ONE).shiftRight(2);

    private G1Compression() {}

    /**
     * Compresses an EIP-2537 uncompressed G1 point.
     *
     * @param uncompressed the 128-byte uncompressed point
     * @param name         the field name used in failure messages
     * @return the 48-byte compressed point
     * @throws com.hedera.node.app.service.clpr.impl.verifier.ProofException if the length is wrong, a padding byte is
     *     not zero, a coordinate is not below the field modulus, the point is the point at infinity, or the point is
     *     not on the curve
     */
    @NonNull
    static byte[] compress(@NonNull final byte[] uncompressed, @NonNull final String name) {
        if (uncompressed.length != UNCOMPRESSED_LENGTH) {
            throw EthProofs.fail(
                    name + " must be " + UNCOMPRESSED_LENGTH + " bytes (EIP-2537 G1), got " + uncompressed.length);
        }
        if (!isZero(uncompressed, 0, PADDING_LENGTH) || !isZero(uncompressed, Y_PADDING_OFFSET, PADDING_LENGTH)) {
            throw EthProofs.fail(name + " has non-zero EIP-2537 padding");
        }
        final byte[] x = Arrays.copyOfRange(uncompressed, X_OFFSET, X_OFFSET + FIELD_ELEMENT_LENGTH);
        final BigInteger xValue = new BigInteger(1, x);
        final BigInteger yValue =
                new BigInteger(1, Arrays.copyOfRange(uncompressed, Y_OFFSET, Y_OFFSET + FIELD_ELEMENT_LENGTH));
        if (xValue.compareTo(FIELD_MODULUS) >= 0 || yValue.compareTo(FIELD_MODULUS) >= 0) {
            throw EthProofs.fail(name + " has a coordinate that is not below the field modulus");
        }
        // No sync-committee key or aggregate is ever the point at infinity.
        if (xValue.signum() == 0 && yValue.signum() == 0) {
            throw EthProofs.fail(name + " is the point at infinity");
        }
        // The compressed form drops y, so only a point of the curve compresses to the key it encodes.
        if (!yValue.multiply(yValue).mod(FIELD_MODULUS).equals(ySquared(xValue))) {
            throw EthProofs.fail(name + " is not a point on the curve");
        }
        // x < p, so its top three bits are free for the flags.
        final int flags = COMPRESSION_FLAG | (yValue.compareTo(HALF_FIELD_MODULUS) > 0 ? SIGN_FLAG : 0);
        x[0] = (byte) (x[0] | flags);
        return x;
    }

    /**
     * Decompresses a ZCash-encoded G1 point to the EIP-2537 uncompressed encoding.
     *
     * @param compressed the 48-byte compressed point
     * @param name       the field name used in failure messages
     * @return the 128-byte uncompressed point
     * @throws com.hedera.node.app.service.clpr.impl.verifier.ProofException if the length is wrong, the compression
     *     flag is clear, the point is the point at infinity, {@code x} is not below the field modulus, or no point of
     *     the curve has that {@code x}
     */
    @NonNull
    static byte[] decompress(@NonNull final byte[] compressed, @NonNull final String name) {
        if (compressed.length != COMPRESSED_LENGTH) {
            throw EthProofs.fail(
                    name + " must be " + COMPRESSED_LENGTH + " bytes (compressed G1), got " + compressed.length);
        }
        final int flags = compressed[0] & FLAG_BITS;
        if ((flags & COMPRESSION_FLAG) == 0) {
            throw EthProofs.fail(name + " does not have the compression flag set");
        }
        // No sync-committee key or aggregate is ever the point at infinity.
        if ((flags & INFINITY_FLAG) != 0) {
            throw EthProofs.fail(name + " is the point at infinity");
        }
        final byte[] x = compressed.clone();
        x[0] = (byte) (x[0] & ~FLAG_BITS);
        final BigInteger xValue = new BigInteger(1, x);
        if (xValue.compareTo(FIELD_MODULUS) >= 0) {
            throw EthProofs.fail(name + " has a coordinate that is not below the field modulus");
        }
        final BigInteger ySquared = ySquared(xValue);
        final BigInteger root = ySquared.modPow(SQUARE_ROOT_EXPONENT, FIELD_MODULUS);
        if (!root.multiply(root).mod(FIELD_MODULUS).equals(ySquared)) {
            throw EthProofs.fail(name + " is not the x coordinate of a point on the curve");
        }
        // The sign flag marks the lexicographically largest of the two roots, y and p - y.
        final boolean largest = (flags & SIGN_FLAG) != 0;
        final BigInteger yValue =
                (root.compareTo(HALF_FIELD_MODULUS) > 0) == largest ? root : FIELD_MODULUS.subtract(root);
        final byte[] uncompressed = new byte[UNCOMPRESSED_LENGTH];
        System.arraycopy(x, 0, uncompressed, X_OFFSET, FIELD_ELEMENT_LENGTH);
        System.arraycopy(toFieldElement(yValue), 0, uncompressed, Y_OFFSET, FIELD_ELEMENT_LENGTH);
        return uncompressed;
    }

    /** {@code x³ + b mod p}: the {@code y²} of the curve points whose abscissa is {@code x}. */
    @NonNull
    private static BigInteger ySquared(@NonNull final BigInteger x) {
        return x.pow(3).add(CURVE_B).mod(FIELD_MODULUS);
    }

    /** {@code value}, below the field modulus, as a 48-byte big-endian field element. */
    @NonNull
    private static byte[] toFieldElement(@NonNull final BigInteger value) {
        final byte[] raw = value.toByteArray();
        final byte[] element = new byte[FIELD_ELEMENT_LENGTH];
        // toByteArray() may prepend a zero sign byte, or return fewer bytes for a small value.
        final int length = Math.min(raw.length, FIELD_ELEMENT_LENGTH);
        System.arraycopy(raw, raw.length - length, element, FIELD_ELEMENT_LENGTH - length, length);
        return element;
    }

    private static boolean isZero(@NonNull final byte[] bytes, final int offset, final int length) {
        for (int i = offset; i < offset + length; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
