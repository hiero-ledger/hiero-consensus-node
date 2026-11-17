// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.clpr.ethereum;

import static org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537.BLS12_G1MULTIEXP_OPERATION_SHIM_VALUE;
import static org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537.BLS12_G2ADD_OPERATION_SHIM_VALUE;
import static org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537.BLS12_G2MULTIEXP_OPERATION_SHIM_VALUE;
import static org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537.BLS12_MAP_FP2_TO_G2_OPERATION_SHIM_VALUE;
import static org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_ERROR_BYTES;
import static org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537.EIP2537_PREALLOCATE_FOR_RESULT_BYTES;

import com.sun.jna.ptr.IntByReference;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.hyperledger.besu.nativelib.gnark.LibGnarkEIP2537;

/**
 * Signs like an Ethereum sync committee member, so the HAPI payloads carry signatures the node's real BLS check
 * accepts: the IETF BLS signature scheme in the ciphersuite {@code BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_POP_}, with
 * public keys in G1 and signatures in G2. Points use the EIP-2537 encoding.
 *
 * <p>The curve arithmetic runs on the EIP-2537 operations of Besu's native gnark library. Hashing a message to G2
 * follows RFC 9380: {@code expand_message_xmd} with SHA-256 gives two Fp2 elements, which the EIP-2537
 * {@code MAP_FP2_TO_G2} operation maps to G2 and clears the cofactor of. Clearing the cofactor is a multiplication by
 * a constant, so the sum of the two mapped points is {@code hash_to_curve}'s {@code clear_cofactor(Q0 + Q1)}.
 *
 * <p>Test use only: the keys are whatever scalars the caller passes.
 */
final class BlsSigner {

    /** The order of the BLS12-381 G1 and G2 subgroups: secret keys are scalars modulo it. */
    static final BigInteger SUBGROUP_ORDER =
            new BigInteger("73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001", 16);

    /** The BLS12-381 base field modulus. */
    private static final BigInteger FIELD_MODULUS = new BigInteger(
            "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab", 16);

    /** The generator of G1, EIP-2537 encoded. */
    private static final byte[] G1_GENERATOR = HexFormat.of()
            .parseHex("0000000000000000000000000000000017f1d3a73197d7942695638c4fa9ac0f"
                    + "c3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
                    + "0000000000000000000000000000000008b3f481e3aaa0f1a09e30ed741d8ae4"
                    + "fcf5e095d5d00af600db18cb2c04b3edd03cc744a2888ae40caa232946c5e7e1");

    /** The domain separation tag of the proof-of-possession ciphersuite, which Ethereum signs with. */
    private static final byte[] DST = "BLS_SIG_BLS12381G2_XMD:SHA-256_SSWU_RO_POP_".getBytes(StandardCharsets.US_ASCII);

    /** The length of an EIP-2537 field element: a 48-byte big-endian value left-padded to 64 bytes. */
    private static final int FIELD_ELEMENT_LENGTH = 64;

    /** The length of an EIP-2537 MSM scalar. */
    private static final int SCALAR_LENGTH = 32;

    /** {@code L} of RFC 9380 for BLS12-381: the bytes hashed into each field element. */
    private static final int BYTES_PER_FIELD_ELEMENT = 64;

    /** {@code hash_to_curve} hashes to two Fp2 elements, of two field elements each. */
    private static final int FIELD_ELEMENTS_HASHED = 4;

    /** {@code s_in_bytes} of SHA-256: its input block size. */
    private static final int SHA256_BLOCK_LENGTH = 64;

    private BlsSigner() {}

    /** The public key of {@code secretKey}: {@code secretKey · G1}. */
    static byte[] publicKey(final BigInteger secretKey) {
        return perform(BLS12_G1MULTIEXP_OPERATION_SHIM_VALUE, concat(G1_GENERATOR, scalar(secretKey)));
    }

    /**
     * The signature of {@code secretKey} over {@code message}: {@code secretKey · H(message)}. Signatures add up, so
     * the aggregate signature of several keys is the signature of the sum of the keys.
     */
    static byte[] sign(final BigInteger secretKey, final byte[] message) {
        return perform(BLS12_G2MULTIEXP_OPERATION_SHIM_VALUE, concat(hashToG2(message), scalar(secretKey)));
    }

    /** {@code hash_to_curve} of RFC 9380 to G2, with the ciphersuite's domain separation tag. */
    private static byte[] hashToG2(final byte[] message) {
        final byte[] uniformBytes = expandMessageXmd(message, FIELD_ELEMENTS_HASHED * BYTES_PER_FIELD_ELEMENT);
        final byte[] q0 = perform(BLS12_MAP_FP2_TO_G2_OPERATION_SHIM_VALUE, fp2(uniformBytes, 0));
        final byte[] q1 = perform(BLS12_MAP_FP2_TO_G2_OPERATION_SHIM_VALUE, fp2(uniformBytes, 2));
        return perform(BLS12_G2ADD_OPERATION_SHIM_VALUE, concat(q0, q1));
    }

    /** {@code expand_message_xmd} of RFC 9380 with SHA-256. */
    private static byte[] expandMessageXmd(final byte[] message, final int length) {
        final byte[] dstPrime = concat(DST, new byte[] {(byte) DST.length});
        final byte[] lengthBytes = {(byte) (length >>> Byte.SIZE), (byte) length};
        final byte[] b0 = sha256(concat(new byte[SHA256_BLOCK_LENGTH], message, lengthBytes, new byte[1], dstPrime));
        final byte[] uniformBytes = new byte[length];
        byte[] previous = new byte[b0.length];
        for (int i = 1; i * b0.length <= length; i++) {
            final byte[] xored = new byte[b0.length];
            for (int j = 0; j < xored.length; j++) {
                xored[j] = (byte) (b0[j] ^ previous[j]);
            }
            previous = sha256(concat(xored, new byte[] {(byte) i}, dstPrime));
            System.arraycopy(previous, 0, uniformBytes, (i - 1) * b0.length, b0.length);
        }
        return uniformBytes;
    }

    /**
     * The Fp2 element made of the field elements {@code first} and {@code first + 1} of {@code uniformBytes}, EIP-2537
     * encoded as {@code c0 ‖ c1}.
     */
    private static byte[] fp2(final byte[] uniformBytes, final int first) {
        return concat(fieldElement(uniformBytes, first), fieldElement(uniformBytes, first + 1));
    }

    /** Field element {@code index} of {@code uniformBytes}: its {@code L} bytes, reduced modulo {@code p}. */
    private static byte[] fieldElement(final byte[] uniformBytes, final int index) {
        final int offset = index * BYTES_PER_FIELD_ELEMENT;
        final BigInteger value = new BigInteger(
                        1, Arrays.copyOfRange(uniformBytes, offset, offset + BYTES_PER_FIELD_ELEMENT))
                .mod(FIELD_MODULUS);
        return leftPad(value.toByteArray(), FIELD_ELEMENT_LENGTH);
    }

    /** {@code value}, reduced modulo the subgroup order, as an EIP-2537 MSM scalar. */
    private static byte[] scalar(final BigInteger value) {
        return leftPad(value.mod(SUBGROUP_ORDER).toByteArray(), SCALAR_LENGTH);
    }

    /** Runs an EIP-2537 operation of the native gnark library. */
    private static byte[] perform(final byte operation, final byte[] input) {
        final byte[] output = new byte[EIP2537_PREALLOCATE_FOR_RESULT_BYTES];
        final IntByReference outputLength = new IntByReference(EIP2537_PREALLOCATE_FOR_RESULT_BYTES);
        final byte[] error = new byte[EIP2537_PREALLOCATE_FOR_ERROR_BYTES];
        final IntByReference errorLength = new IntByReference(EIP2537_PREALLOCATE_FOR_ERROR_BYTES);
        final int status = LibGnarkEIP2537.eip2537_perform_operation(
                operation, input, input.length, output, outputLength, error, errorLength);
        if (status != 0) {
            throw new IllegalStateException("EIP-2537 operation " + operation + " failed: "
                    + new String(error, 0, errorLength.getValue(), StandardCharsets.UTF_8));
        }
        return Arrays.copyOf(output, outputLength.getValue());
    }

    /** {@code bytes}, a big-endian unsigned value, left-padded or stripped of its sign byte to {@code length}. */
    private static byte[] leftPad(final byte[] bytes, final int length) {
        final byte[] padded = new byte[length];
        final int copied = Math.min(bytes.length, length);
        System.arraycopy(bytes, bytes.length - copied, padded, length - copied, copied);
        return padded;
    }

    private static byte[] sha256(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] concat(final byte[]... parts) {
        int length = 0;
        for (final byte[] part : parts) {
            length += part.length;
        }
        final byte[] joined = new byte[length];
        int offset = 0;
        for (final byte[] part : parts) {
            System.arraycopy(part, 0, joined, offset, part.length);
            offset += part.length;
        }
        return joined;
    }
}
