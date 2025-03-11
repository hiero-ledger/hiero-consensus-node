// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.sun.jna.ptr.LongByReference;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

public class MiscCryptoUtils {
    private static final int EVM_ADDRESS_SIZE = 20;

    /**
     * Record for caching thread local variables to avoid allocating memory for each verification.
     */
    private record ThreadLocalCache(
            LibSecp256k1.secp256k1_pubkey pubKey,
            byte[] uncompressedPublicKeyInput,
            ByteBuffer uncompressedPublicKeyByteBuffer,
            LongByReference length) {
        public ThreadLocalCache() {
            this(
                    new LibSecp256k1.secp256k1_pubkey(),
                    new byte[ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE],
                    ByteBuffer.allocate(ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE),
                    new LongByReference());
            // set the type header byte for uncompressed public keys, this is always the same
            uncompressedPublicKeyInput[0] = 0x04;
        }
    }

    /** Length of an uncompressed ECDSA public key */
    private static final int ECDSA_UNCOMPRESSED_KEY_SIZE = 64;

    /** Length of an uncompressed ECDSA public key including a header byte */
    private static final int ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE = ECDSA_UNCOMPRESSED_KEY_SIZE + 1;

    /**
     * Thread local caches to avoid allocating memory for each verification. They will leak memory for each thread used
     * for verification but only just over 100 bytes to totally worth it.
     */
    private static final ThreadLocal<ThreadLocalCache> CACHE = ThreadLocal.withInitial(ThreadLocalCache::new);

    private MiscCryptoUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static byte[] keccak256DigestOf(final byte[] msg) {
        return new Keccak.Digest256().digest(msg);
    }

    public static Bytes keccak256DigestOf(final Bytes msg) {
        final MessageDigest digest = new Keccak.Digest256();
        msg.writeTo(digest);
        return Bytes.wrap(digest.digest());
    }

    /**
     * Given a 33-byte compressed ECDSA(secp256k1) public key, returns the uncompressed key as a
     * 64-byte array whose first 32 bytes are the x-coordinate of the key and second 32 bytes are
     * the y-coordinate of the key.
     *
     * @param compressedKey a compressed ECDSA(secp256k1) public key
     * @return the raw bytes of the public key coordinates
     * @throws IllegalArgumentException if the compressed key not parsable
     */
    public static byte[] decompressSecp256k1(final byte[] compressedKey) {
        final ThreadLocalCache cache = CACHE.get();
        // convert public key to native format
        final LibSecp256k1.secp256k1_pubkey publicKey = cache.pubKey;
        final int keyParseResult = LibSecp256k1.secp256k1_ec_pubkey_parse(
                LibSecp256k1.CONTEXT, publicKey, compressedKey, compressedKey.length);
        if (keyParseResult != 1) throw new IllegalArgumentException("Failed to parse public key");
        final ByteBuffer outputBuffer = cache.uncompressedPublicKeyByteBuffer;
        final LongByReference outputLength = cache.length;
        outputLength.setValue(ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE);
        final int keySerializeResult = LibSecp256k1.secp256k1_ec_pubkey_serialize(
                LibSecp256k1.CONTEXT, outputBuffer, outputLength, publicKey, LibSecp256k1.SECP256K1_EC_UNCOMPRESSED);
        if (keySerializeResult != 1) throw new IllegalArgumentException("Failed to serialize public key");
        // chop off header first byte
        final var rawKey = new byte[64];
        outputBuffer.get(1, rawKey);
        return rawKey;
    }

    /**
     * Given a 64-byte decompressed ECDSA(secp256k1) public key, returns the compressed key as a
     * 33-byte array whose first byte is the parity of the y coordinate and the following 32 bytes
     * are the x-coordinate of the key.
     *
     * @param decompressedKey a decompressed ECDSA(secp256k1) public key
     * @return the raw bytes of the compressed public key
     * @throws IllegalArgumentException if the decompressed key not parsable
     */
    public static byte[] compressSecp256k1(final byte[] decompressedKey) {
        final ThreadLocalCache cache = CACHE.get();
        // add header byte on to decompressed key
        final byte[] decompressedBytes = cache.uncompressedPublicKeyInput;
        System.arraycopy(decompressedKey, 0, decompressedBytes, 1, ECDSA_UNCOMPRESSED_KEY_SIZE);
        // convert public key to native format
        final LibSecp256k1.secp256k1_pubkey publicKey = cache.pubKey;
        final int keyParseResult = LibSecp256k1.secp256k1_ec_pubkey_parse(
                LibSecp256k1.CONTEXT, publicKey, decompressedBytes, decompressedBytes.length);
        if (keyParseResult != 1) throw new IllegalArgumentException("Failed to parse public key");
        // serialize public key to compressed format
        final ByteBuffer outputBuffer = ByteBuffer.allocate(33);
        final LongByReference outputLength = cache.length;
        outputLength.setValue(33);
        final int keySerializeResult = LibSecp256k1.secp256k1_ec_pubkey_serialize(
                LibSecp256k1.CONTEXT, outputBuffer, outputLength, publicKey, LibSecp256k1.SECP256K1_EC_COMPRESSED);
        if (keySerializeResult != 1) throw new IllegalArgumentException("Failed to serialize public key");
        return outputBuffer.array();
    }

    /**
     * Given a 64-byte decompressed ECDSA(secp256k1) public key, returns the evm address
     * derived from the last 20 bytes of the keccak256 hash of the public key.
     *
     * @param decompressedKey a decompressed ECDSA(secp256k1) public key
     * @return the raw bytes of the evm address derived from that key
     */
    public static byte[] extractEvmAddressFromDecompressedECDSAKey(final byte[] decompressedKey) {
        final var publicKeyHash = MiscCryptoUtils.keccak256DigestOf(decompressedKey);
        return Arrays.copyOfRange(publicKeyHash, publicKeyHash.length - EVM_ADDRESS_SIZE, publicKeyHash.length);
    }
}
