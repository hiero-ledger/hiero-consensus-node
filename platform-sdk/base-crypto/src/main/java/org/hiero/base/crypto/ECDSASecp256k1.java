// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import com.hedera.cryptography.libsecp256k1.ContextualLibsecp256k1;
import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import com.hedera.cryptography.libsecp256k1.Secp256k1ECPrivateKey;
import com.hedera.cryptography.libsecp256k1.Secp256k1ECPublicKey;
import java.lang.foreign.MemorySegment;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.util.Arrays;

/// A utility class to support ECDSA secp256k1-based cryptography.
public final class ECDSASecp256k1 {
    private static final ContextualLibsecp256k1 LIBSECP256K1 = ContextualLibsecp256k1.getInstance();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ECDSASecp256k1() {}

    /// Extracts 32-byte secp256k1 EcDSA private key from a PrivateKey instance.
    public static byte[] asPrivateKeyByteArray32(final ECPrivateKey privateKey) {
        if (privateKey instanceof Secp256k1ECPrivateKey(byte[] secretKey)) {
            return secretKey;
        }
        return asPrivateKeyByteArray32(privateKey.getS());
    }

    /// Returns an unsigned byte[] representation of a BigInteger, dropping the leading zero byte
    /// if present, aligning remaining bytes to the right, and padding with zeros to 32 bytes.
    public static byte[] asPrivateKeyByteArray32(final BigInteger value) {
        validatePrivateKeyS(value);
        return asPrivateKeyByteArray32(value.toByteArray());
    }

    /// Validates ECPrivateKey.getS() value to be in range (0, SECP256K1_CURVE_N).
    public static void validatePrivateKeyS(final BigInteger s) {
        if (s.signum() <= 0 || s.compareTo(Secp256k1ECPrivateKey.SECP256K1_CURVE_N) >= 0) {
            throw new IllegalArgumentException("BigInteger is out of range (0, SECP256K1_CURVE_N)");
        }
    }

    /// Accepts an array of 33 or fewer bytes, dropping the leading "33rd" zero byte, aligning
    /// remaining bytes to the right, and padding with zeros to 32 bytes if needed.
    /// The result is a byte[32], or an IllegalArgumentException if the input is malformed.
    public static byte[] asPrivateKeyByteArray32(final byte[] bytes) {
        if (bytes.length == 32) {
            return bytes;
        } else {
            final byte[] tmp = new byte[32];

            if (bytes.length == 33 && bytes[0] == 0) {
                System.arraycopy(bytes, 1, tmp, 0, 32);
                return tmp;
            } else if (bytes.length < 32) {
                System.arraycopy(bytes, 0, tmp, 32 - bytes.length, bytes.length);
                return tmp;
            }
        }

        // The byte array is longer than 33 bytes. It's an invalid secp256k1 key.
        throw new IllegalArgumentException("Invalid BigInteger that is too long. It cannot be a valid private key.");
    }

    /**
     * Returns a randomly generated ECDSA(secp256k1) key pair.
     *
     * @return a random key pair
     */
    public static KeyPair genEcdsaSecp256k1KeyPair() {
        // Generate raw secret key bytes
        final byte[] secretKeyBytes = new byte[Libsecp256k1.SECRET_KEY_BYTES];
        SECURE_RANDOM.nextBytes(secretKeyBytes);

        // Generate the corresponding public key in its native, internal form
        final byte[] publicKeyBytes = new byte[Libsecp256k1.PUBLIC_KEY_BYTES];
        int ret = LIBSECP256K1.secp256k1EcPubkeyCreate(
                MemorySegment.ofArray(publicKeyBytes), MemorySegment.ofArray(secretKeyBytes));
        if (ret != 1) {
            throw new IllegalStateException("secp256k1EcPubkeyCreate returned " + ret);
        }

        // Convert the native, internal form into a portable public key form
        final byte[] serializedPortablePublicKeyBytes = new byte[Libsecp256k1.PUBLIC_KEY_BYTES + 1];
        final long[] length = new long[] {serializedPortablePublicKeyBytes.length};
        ret = LIBSECP256K1.secp256k1EcPubkeySerialize(
                MemorySegment.ofArray(serializedPortablePublicKeyBytes),
                MemorySegment.ofArray(length),
                MemorySegment.ofArray(publicKeyBytes),
                Libsecp256k1.SECP256K1_EC_UNCOMPRESSED);
        if (ret != 1) {
            throw new IllegalStateException("secp256k1EcPubkeySerialize returned " + ret);
        }
        final byte[] portablePublicKeyBytes =
                Arrays.copyOfRange(serializedPortablePublicKeyBytes, 1, serializedPortablePublicKeyBytes.length);

        // Return the keypair
        final Secp256k1ECPrivateKey privateKey = new Secp256k1ECPrivateKey(secretKeyBytes);
        final Secp256k1ECPublicKey publicKey = new Secp256k1ECPublicKey(portablePublicKeyBytes);
        return new KeyPair(publicKey, privateKey);
    }

    /**
     * Signs the given digest with the given ECDSA(secp256k1) private key and returns the
     * raw signature; that is, the 64-byte concatenation of the signature's {@code r} value
     * followed by its {@code s} value in unsigned big-endian form.
     *
     * @param privateKey
     * 		the private key to use
     * @return the raw signature of the given digest
     */
    public static byte[] signDigestWithEcdsaSecp256k1(final PrivateKey privateKey, final byte[] digest) {
        final byte[] secretKey = asPrivateKeyByteArray32((ECPrivateKey) privateKey);
        return signDigestWithEcdsaSecp256k1(secretKey, digest);
    }

    /**
     * Signs the given digest with the given native ECDSA(secp256k1) secret key and returns the
     * raw signature; that is, the 64-byte concatenation of the signature's {@code r} value
     * followed by its {@code s} value in unsigned big-endian form.
     *
     * @param secretKey a native 32-bytes ECDSA secp256k1 secret key
     * @return the raw signature of the given digest
     */
    public static byte[] signDigestWithEcdsaSecp256k1(final byte[] secretKey, final byte[] digest) {
        final byte[] recSignature = new byte[Libsecp256k1.RECOVERABLE_SIGNATURE_BYTES];
        final MemorySegment recSignatureSeg = MemorySegment.ofArray(recSignature);
        int ret = LIBSECP256K1.secp256k1EcdsaSignRecoverable(
                recSignatureSeg,
                MemorySegment.ofArray(digest),
                MemorySegment.ofArray(secretKey),
                MemorySegment.NULL,
                MemorySegment.NULL);
        if (ret != 1) {
            throw new IllegalStateException("secp256k1EcdsaSignRecoverable returned " + ret);
        }

        final byte[] signature = new byte[Libsecp256k1.SIGNATURE_BYTES];
        final int[] recId = new int[1];
        ret = LIBSECP256K1.secp256k1EcdsaRecoverableSignatureSerializeCompact(
                MemorySegment.ofArray(signature), MemorySegment.ofArray(recId), recSignatureSeg);
        if (ret != 1) {
            throw new IllegalStateException("secp256k1EcdsaRecoverableSignatureSerializeCompact returned " + ret);
        }

        return signature;
    }
}
