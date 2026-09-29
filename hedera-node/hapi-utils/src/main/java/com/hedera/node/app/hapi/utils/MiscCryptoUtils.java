// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import com.hedera.cryptography.libsecp256k1.ContextualLibsecp256k1;
import com.hedera.cryptography.libsecp256k1.Libsecp256k1;
import com.hedera.cryptography.libxkcp.Libxkcp;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

public class MiscCryptoUtils {
    private static final ContextualLibsecp256k1 LIBSECP256K1 = ContextualLibsecp256k1.getInstance();
    private static final Libxkcp LIBXKCP = Libxkcp.getInstance();

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

    public static byte[] keccak256DigestOf(final MemorySegment msgSegment) {
        final byte[] hashInstance = new byte[LIBXKCP.sizeOfKeccakHashInstance];
        final MemorySegment hashInstanceSeg = MemorySegment.ofArray(hashInstance);
        int ret;

        ret = LIBXKCP.keccakHashInitialize(
                hashInstanceSeg,
                Libxkcp.SHA3_256_RATE,
                Libxkcp.SHA3_256_CAPACITY,
                Libxkcp.SHA3_256_HASHBITLEN,
                Libxkcp.SHA3_256_DELIMITED_SUFFIX_ORIGINAL);
        if (ret != Libxkcp.KECCAK_SUCCESS) {
            throw new RuntimeException("keccakHashInitialize returned " + ret);
        }

        // Libxkcp currently requires a non-zero length of data to hash.
        // We'll support this eventually: https://github.com/hiero-ledger/hiero-cryptography/issues/679
        // But we cannot upgrade hiero-cryptography now due to a set of incompatible changes there.
        if (msgSegment.byteSize() > 0) {
            ret = LIBXKCP.keccakHashUpdate(hashInstanceSeg, msgSegment, msgSegment.byteSize() * 8L);
            if (ret != Libxkcp.KECCAK_SUCCESS) {
                throw new RuntimeException("keccakHashUpdate returned " + ret);
            }
        }

        final byte[] hash = new byte[Libxkcp.SHA3_256_HASHVAL_LENGTH_BYTES];
        ret = LIBXKCP.keccakHashFinal(hashInstanceSeg, MemorySegment.ofArray(hash));
        if (ret != Libxkcp.KECCAK_SUCCESS) {
            throw new RuntimeException("keccakHashFinal returned " + ret);
        }
        return hash;
    }

    public static byte[] keccak256DigestOf(final byte[] msg) {
        return keccak256DigestOf(MemorySegment.ofArray(msg));
    }

    public static Bytes keccak256DigestOf(final Bytes msg) {
        return Bytes.wrap(keccak256DigestOf(msg.toMemorySegment()));
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
     * Given a 64-byte decompressed ECDSA(secp256k1) public key, returns the compressed key as a
     * 33-byte array whose first byte is the parity of the y coordinate and the following 32 bytes
     * are the x-coordinate of the key.
     *
     * @param decompressedKey a decompressed ECDSA(secp256k1) public key
     * @return the compressed public key bytes
     * @throws IllegalArgumentException if the decompressed key is not parsable
     */
    public static byte[] compressSecp256k1(final byte[] decompressedKey) {
        final ThreadLocalCache cache = CACHE.get();
        System.arraycopy(decompressedKey, 0, cache.uncompressedPublicKeyInput, 1, ECDSA_UNCOMPRESSED_KEY_SIZE);
        final int keyParseResult = LIBSECP256K1.secp256k1EcPubkeyParse(
                cache.pubKeySeg, cache.uncompressedPublicKeyInputSeg, cache.uncompressedPublicKeyInput.length);
        if (keyParseResult != 1) throw new IllegalArgumentException("Failed to parse public key");

        final byte[] compressedKey = new byte[33];
        final long[] compressedKeyLength = {compressedKey.length};
        final int keySerializeResult = LIBSECP256K1.secp256k1EcPubkeySerialize(
                MemorySegment.ofArray(compressedKey),
                MemorySegment.ofArray(compressedKeyLength),
                cache.pubKeySeg,
                Libsecp256k1.SECP256K1_EC_COMPRESSED);
        if (keySerializeResult != 1) throw new IllegalArgumentException("Failed to serialize public key");
        return compressedKey;
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
