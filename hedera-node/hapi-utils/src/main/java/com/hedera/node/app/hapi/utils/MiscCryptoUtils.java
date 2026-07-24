// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hedera.cryptography.libsecp256k1.ContextualLibsecp256k1;
import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.lang.foreign.MemorySegment;
import java.security.MessageDigest;
import java.util.Arrays;
import org.bouncycastle.jcajce.provider.digest.Keccak;

public class MiscCryptoUtils {
    private static final ContextualLibsecp256k1 LIBSECP256K1 = ContextualLibsecp256k1.getInstance();

    private static final int EVM_ADDRESS_SIZE = 20;

    /**
     * Record for caching thread local variables to avoid allocating memory for each verification.
     */
    private record ThreadLocalCache(
            byte[] pubKey,
            MemorySegment pubKeySeg,
            byte[] uncompressedPublicKeyInput,
            MemorySegment uncompressedPublicKeyInputSeg,
            byte[] uncompressedPublicKeyByteBuffer,
            MemorySegment uncompressedPublicKeyByteBufferSeg,
            long[] length,
            MemorySegment lengthSeg) {
        public ThreadLocalCache() {
            byte[] pubKey = new byte[Libsecp256k1.PUBLIC_KEY_BYTES];
            byte[] uncompressedPublicKeyInput = new byte[ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE];
            byte[] uncompressedPublicKeyByteBuffer = new byte[ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE];
            long[] length = new long[1];

            this(
                    pubKey,
                    MemorySegment.ofArray(pubKey),
                    uncompressedPublicKeyInput,
                    MemorySegment.ofArray(uncompressedPublicKeyInput),
                    uncompressedPublicKeyByteBuffer,
                    MemorySegment.ofArray(uncompressedPublicKeyByteBuffer),
                    length,
                    MemorySegment.ofArray(length));

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
        final int keyParseResult = LIBSECP256K1.secp256k1EcPubkeyParse(
                cache.pubKeySeg, MemorySegment.ofArray(compressedKey), compressedKey.length);
        if (keyParseResult != 1) throw new IllegalArgumentException("Failed to parse public key");
        cache.length[0] = ECDSA_UNCOMPRESSED_KEY_SIZE_WITH_HEADER_BYTE;
        final int keySerializeResult = LIBSECP256K1.secp256k1EcPubkeySerialize(
                cache.uncompressedPublicKeyByteBufferSeg,
                cache.lengthSeg,
                cache.pubKeySeg,
                Libsecp256k1.SECP256K1_EC_UNCOMPRESSED);
        if (keySerializeResult != 1) throw new IllegalArgumentException("Failed to serialize public key");
        // chop off header first byte
        final var rawKey = new byte[64];
        System.arraycopy(cache.uncompressedPublicKeyByteBuffer, 1, rawKey, 0, rawKey.length);
        return rawKey;
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
