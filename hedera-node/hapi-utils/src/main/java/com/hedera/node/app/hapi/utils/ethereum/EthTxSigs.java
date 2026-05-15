// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.ethereum;

import static com.hedera.node.app.hapi.utils.ethereum.EthTxData.SECP256K1_EC_COMPRESSED;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.CONTEXT;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.SECP256K1_EC_UNCOMPRESSED;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.secp256k1_ec_pubkey_serialize;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.secp256k1_ecdsa_recover;
import static org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1.secp256k1_ecdsa_recoverable_signature_parse_compact;

import com.esaulpaugh.headlong.rlp.RLPEncoder;
import com.esaulpaugh.headlong.util.Integers;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.sun.jna.ptr.LongByReference;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.hyperledger.besu.nativelib.secp256k1.LibSecp256k1;

public record EthTxSigs(byte[] publicKey, byte[] address) {
    private static final BigInteger N = SECNamedCurves.getByName("secp256k1").getN();

    /**
     * Big-endian, fixed-width 32-byte representation of the secp256k1 curve order {@code N},
     * used for the fast, allocation-free bounds check in {@link #checkInBounds(byte[])}.
     */
    private static final byte[] N_BYTES = toUnsignedFixed32(N);

    private static final int PUBKEY_COMPRESSED_LEN = 33;
    private static final int PUBKEY_UNCOMPRESSED_LEN = 65;
    private static final int SIG_RS_LEN = 64;
    private static final int ADDRESS_LEN = 20;
    private static final int KECCAK_OUT_LEN = 32;

    /**
     * Thread-local set of reusable allocations for the secp256k1 recovery hot path.
     *
     * <p>Profiling of the Ethereum contract handler (`EthTxSigsCache.computeIfAbsent` →
     * `EthTxSigs.extractSignatures`) shows the per-call allocations of the two JNA
     * {@code Structure}s, the two {@code ByteBuffer}s, the {@code LongByReference}, the padded
     * signature {@code byte[64]}, the address scratch {@code byte[64]} and the {@code Keccak.Digest256}
     * dominate the recovery cost. Caching them per thread eliminates that pressure entirely.
     *
     * <p>Reusing the JNA Structures is safe: each {@code LibSecp256k1.*} call writes Java fields
     * into the backing native memory before the call and reads them back afterward, and the
     * native functions overwrite the {@code data} field on output, so no manual reset is needed.
     */
    private record Cache(
            LibSecp256k1.secp256k1_ecdsa_recoverable_signature parsedSignature,
            LibSecp256k1.secp256k1_pubkey pubKey,
            byte[] paddedSig,
            ByteBuffer compressedKeyBuf,
            ByteBuffer uncompressedKeyBuf,
            LongByReference keySize,
            Keccak.Digest256 keccak,
            byte[] addressPreHash) {
        Cache() {
            this(
                    new LibSecp256k1.secp256k1_ecdsa_recoverable_signature(),
                    new LibSecp256k1.secp256k1_pubkey(),
                    new byte[SIG_RS_LEN],
                    ByteBuffer.allocate(PUBKEY_COMPRESSED_LEN),
                    ByteBuffer.allocate(PUBKEY_UNCOMPRESSED_LEN),
                    new LongByReference(),
                    new Keccak.Digest256(),
                    new byte[PUBKEY_UNCOMPRESSED_LEN - 1]);
        }
    }

    private static final ThreadLocal<Cache> CACHE = ThreadLocal.withInitial(Cache::new);

    public static EthTxSigs extractSignatures(EthTxData ethTx) {
        final var message = calculateSignableMessage(ethTx);
        return recoverSigs(ethTx.recId(), ethTx.r(), ethTx.s(), message);
    }

    public static Optional<EthTxSigs> extractAuthoritySignature(CodeDelegation codeDelegation) {
        try {
            final var message = codeDelegation.calculateSignableMessage();
            return Optional.of(recoverSigs(codeDelegation.yParity(), codeDelegation.r(), codeDelegation.s(), message));
        } catch (final Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Hot-path recovery that uses the thread-local {@link Cache} so the only heap allocations are
     * the two output {@code byte[]} (compressed public key and 20-byte address) and the
     * enclosing {@link EthTxSigs} record. The recovered {@link LibSecp256k1.secp256k1_pubkey} is
     * derived directly into the thread-local Structure; address and compressed-key bytes are then
     * serialized out of it using the cached buffers.
     */
    @VisibleForTesting
    static EthTxSigs recoverSigs(int recId, byte[] r, byte[] s, byte[] message) {
        // The only meaningful recovery ids are 0 and 1 (even if the high order bytes
        // were used to encode the chain id, the parity is all that matters here)
        recId = Math.floorMod(recId, 2);

        final Cache cache = CACHE.get();

        // Bounds check on (r, s) against the secp256k1 curve order N; throws on out-of-bounds.
        checkInBounds(r);
        checkInBounds(s);

        // Hash the message into the cached Keccak instance; `digest(byte[])` calls `engineReset()`
        // after producing the output so the next call starts clean.
        final byte[] dataHash = cache.keccak.digest(message);

        // The RLP library output won't include leading zeros, which means a simple (r, s)
        // concatenation breaks signature verification below.
        concatLeftPaddedInto(cache.paddedSig, r, s);

        final LibSecp256k1.secp256k1_ecdsa_recoverable_signature parsedSignature = cache.parsedSignature;
        if (secp256k1_ecdsa_recoverable_signature_parse_compact(CONTEXT, parsedSignature, cache.paddedSig, recId)
                == 0) {
            throw new IllegalArgumentException("Could not parse signature");
        }
        final LibSecp256k1.secp256k1_pubkey newPubKey = cache.pubKey;
        if (secp256k1_ecdsa_recover(CONTEXT, newPubKey, parsedSignature, dataHash) == 0) {
            throw new IllegalArgumentException("Could not recover signature");
        }

        final byte[] compressedKey = serializeCompressedInto(newPubKey, cache);
        final byte[] address = recoverAddressInto(newPubKey, cache);
        return new EthTxSigs(compressedKey, address);
    }

    public static byte[] calculateSignableMessage(EthTxData ethTx) {
        return switch (ethTx.type()) {
            case LEGACY_ETHEREUM -> resolveLegacy(ethTx);
            case EIP1559 -> resolveEIP1559(ethTx);
            case EIP2930 -> resolveEIP2930(ethTx);
            case EIP7702 -> resolveEIP7702(ethTx);
        };
    }

    // Legacy transactions do not support EIP1559, so only a single gasPrice field is present.
    // Additionally, they do not include access list information.
    static byte[] resolveLegacy(final EthTxData ethTx) {
        return ethTx.chainId() != null && ethTx.chainId().length > 0
                ? RLPEncoder.list(
                        Integers.toBytes(ethTx.nonce()),
                        ethTx.gasPrice(),
                        Integers.toBytes(ethTx.gasLimit()),
                        ethTx.to(),
                        Integers.toBytesUnsigned(ethTx.value()),
                        ethTx.callData(),
                        ethTx.chainId(),
                        Integers.toBytes(0),
                        Integers.toBytes(0))
                : RLPEncoder.list(
                        Integers.toBytes(ethTx.nonce()),
                        ethTx.gasPrice(),
                        Integers.toBytes(ethTx.gasLimit()),
                        ethTx.to(),
                        Integers.toBytesUnsigned(ethTx.value()),
                        ethTx.callData());
    }

    // A notable difference introduced in EIP1559 is the replacement of the gasPrice field
    // with maxPriorityGas and maxGas fields, enabling more granular control over transaction fees.
    // More details: https://eips.ethereum.org/EIPS/eip-1559
    static byte[] resolveEIP1559(final EthTxData ethTx) {
        return RLPEncoder.sequence(Integers.toBytes(2), new Object[] {
            ethTx.chainId(),
            Integers.toBytes(ethTx.nonce()),
            ethTx.maxPriorityGas(),
            ethTx.maxGas(),
            Integers.toBytes(ethTx.gasLimit()),
            ethTx.to(),
            Integers.toBytesUnsigned(ethTx.value()),
            ethTx.callData(),
            ethTx.accessListAsRlp() != null ? ethTx.accessListAsRlp() : new Object[0]
        });
    }

    // EIP2930 introduces the accessList field, which allows specifying a list of
    // addresses and storage keys the transaction will access.
    // More details: https://eips.ethereum.org/EIPS/eip-2930
    static byte[] resolveEIP2930(final EthTxData ethTx) {
        return RLPEncoder.sequence(Integers.toBytes(1), new Object[] {
            ethTx.chainId(),
            Integers.toBytes(ethTx.nonce()),
            ethTx.gasPrice(),
            Integers.toBytes(ethTx.gasLimit()),
            ethTx.to(),
            Integers.toBytesUnsigned(ethTx.value()),
            ethTx.callData(),
            ethTx.accessListAsRlp() != null ? ethTx.accessListAsRlp() : new Object[0]
        });
    }

    // EIP7702 introduces the authorizationList field, which allows one to specify
    // a list of EOA addresses (via signatures) that are then associated with the code of a contract
    // Like EIP1559, it uses maxPriorityGas and maxGas for fee control.
    // More details: https://eips.ethereum.org/EIPS/eip-7702
    static byte[] resolveEIP7702(final EthTxData ethTx) {
        return RLPEncoder.sequence(Integers.toBytes(4), new Object[] {
            ethTx.chainId(),
            Integers.toBytes(ethTx.nonce()),
            ethTx.maxPriorityGas(),
            ethTx.maxGas(),
            Integers.toBytes(ethTx.gasLimit()),
            ethTx.to(),
            Integers.toBytesUnsigned(ethTx.value()),
            ethTx.callData(),
            ethTx.accessListAsRlp() != null ? ethTx.accessListAsRlp() : new Object[0],
            ethTx.authorizationListAsRlp() != null ? ethTx.authorizationListAsRlp() : new Object[0]
        });
    }

    /**
     * Allocating variant kept for backward compatibility / external callers; the hot recovery
     * path in this class goes through {@link #recoverSigs(int, byte[], byte[], byte[])} which
     * uses the thread-local {@link Cache} instead.
     */
    static byte[] serializeIntoCompressedKeyBytes(LibSecp256k1.secp256k1_pubkey pubKey) {
        final ByteBuffer recoveredFullKey = ByteBuffer.allocate(PUBKEY_COMPRESSED_LEN);
        final LongByReference fullKeySize = new LongByReference(recoveredFullKey.limit());
        LibSecp256k1.secp256k1_ec_pubkey_serialize(
                CONTEXT, recoveredFullKey, fullKeySize, pubKey, SECP256K1_EC_COMPRESSED);
        return recoveredFullKey.array();
    }

    /**
     * Allocating variant kept for backward compatibility / external callers; the hot recovery
     * path in this class goes through {@link #recoverSigs(int, byte[], byte[], byte[])} which
     * uses the thread-local {@link Cache} instead.
     */
    static LibSecp256k1.secp256k1_pubkey extractSig(int recId, byte[] r, byte[] s, byte[] message) {
        // The only meaningful recovery ids are 0 and 1 (even if the high order bytes
        // were used to encode the chain id, the parity is all that matters here)
        recId = Math.floorMod(recId, 2);

        byte[] dataHash = new Keccak.Digest256().digest(message);

        checkInBounds(r);
        checkInBounds(s);
        // The RLP library output won't include leading zeros, which means
        // a simple (r, s) concatenation breaks signature verification below
        byte[] signature = concatLeftPadded(r, s);

        final LibSecp256k1.secp256k1_ecdsa_recoverable_signature parsedSignature =
                new LibSecp256k1.secp256k1_ecdsa_recoverable_signature();

        if (secp256k1_ecdsa_recoverable_signature_parse_compact(CONTEXT, parsedSignature, signature, recId) == 0) {
            throw new IllegalArgumentException("Could not parse signature");
        }
        final LibSecp256k1.secp256k1_pubkey newPubKey = new LibSecp256k1.secp256k1_pubkey();
        if (secp256k1_ecdsa_recover(CONTEXT, newPubKey, parsedSignature, dataHash) == 0) {
            throw new IllegalArgumentException("Could not recover signature");
        } else {
            return newPubKey;
        }
    }

    @VisibleForTesting
    static byte[] concatLeftPadded(final byte[] r, final byte[] s) {
        byte[] signature = new byte[SIG_RS_LEN];
        concatLeftPaddedInto(signature, r, s);
        return signature;
    }

    /**
     * Fills {@code dest} (must be 64 bytes long) with the 32-byte big-endian, left-zero-padded
     * representations of {@code r} and {@code s}. Used on the hot path with the thread-local
     * buffer to avoid allocating a fresh signature {@code byte[64]} per recovery.
     */
    private static void concatLeftPaddedInto(byte[] dest, byte[] r, byte[] s) {
        Arrays.fill(dest, (byte) 0);
        final int rLeadingZeros = 32 - r.length;
        System.arraycopy(r, 0, dest, rLeadingZeros, r.length);
        final int sLeadingZeros = 32 - s.length;
        System.arraycopy(s, 0, dest, 32 + sLeadingZeros, s.length);
    }

    /**
     * Serializes {@code pubKey} into a compressed (33-byte) representation using the cached
     * {@link ByteBuffer} and {@link LongByReference}. The caller receives a fresh 33-byte array
     * (the buffer's backing array is returned by reference to match the existing contract).
     */
    private static byte[] serializeCompressedInto(LibSecp256k1.secp256k1_pubkey pubKey, Cache cache) {
        final ByteBuffer buf = cache.compressedKeyBuf;
        buf.clear();
        cache.keySize.setValue(buf.limit());
        secp256k1_ec_pubkey_serialize(CONTEXT, buf, cache.keySize, pubKey, SECP256K1_EC_COMPRESSED);
        // The native call writes into `buf`'s backing array; return a defensive copy so the
        // thread-local buffer can be reused for the next call without aliasing the result.
        return Arrays.copyOf(buf.array(), PUBKEY_COMPRESSED_LEN);
    }

    /**
     * Computes the 20-byte EVM address from {@code pubKey} using the cached uncompressed-key
     * buffer, address pre-hash buffer and Keccak instance. Equivalent in behavior to
     * {@link com.hedera.node.app.hapi.utils.EthSigsUtils#recoverAddressFromPubKey(LibSecp256k1.secp256k1_pubkey)}
     * but allocates only the 20-byte output array.
     */
    private static byte[] recoverAddressInto(LibSecp256k1.secp256k1_pubkey pubKey, Cache cache) {
        final ByteBuffer buf = cache.uncompressedKeyBuf;
        buf.clear();
        cache.keySize.setValue(buf.limit());
        secp256k1_ec_pubkey_serialize(CONTEXT, buf, cache.keySize, pubKey, SECP256K1_EC_UNCOMPRESSED);

        // The native serializer writes 65 bytes: a 1-byte uncompressed type header (0x04)
        // followed by the 64-byte (x || y) representation. The address hash is keccak256 of the
        // (x || y) portion only; we skip the header byte.
        final byte[] backing = buf.array();
        final byte[] preHash = cache.addressPreHash;
        System.arraycopy(backing, 1, preHash, 0, PUBKEY_UNCOMPRESSED_LEN - 1);
        final byte[] keyHash = cache.keccak.digest(preHash);

        final byte[] address = new byte[ADDRESS_LEN];
        System.arraycopy(keyHash, KECCAK_OUT_LEN - ADDRESS_LEN, address, 0, ADDRESS_LEN);
        return address;
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;

        final EthTxSigs ethTxSigs = (EthTxSigs) other;

        if (!Arrays.equals(publicKey, ethTxSigs.publicKey)) return false;
        return Arrays.equals(address, ethTxSigs.address);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(publicKey);
        result = 31 * result + Arrays.hashCode(address);
        return result;
    }

    @Override
    @NonNull
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("publicKey", Hex.encodeHexString(publicKey))
                .add("address", Hex.encodeHexString(address))
                .toString();
    }

    /**
     * Returns whether the given curve point is in bounds for the Secp256k1 curve.
     *
     * <p>Implemented as an unsigned big-endian byte comparison against the precomputed 32-byte
     * representation of {@code N}, so the check allocates nothing (the previous implementation
     * allocated a fresh {@link BigInteger} per call, which was visible on the contract handler
     * hot path).
     *
     * @param curvePoint the curve point to check
     */
    @VisibleForTesting
    static void checkInBounds(@NonNull byte[] curvePoint) {
        if (isAllZeros(curvePoint)) {
            throw new IllegalArgumentException("Curve point must be >= 1");
        }
        // RLP strips leading zeros; any byte array shorter than 32 bytes is strictly less than N
        // (which is a 256-bit value), so we only have to do the full compare at length == 32.
        // Anything longer than 32 bytes is invalid by construction.
        if (curvePoint.length > 32) {
            throw new IllegalArgumentException("Curve point must be < N");
        }
        if (curvePoint.length < 32) {
            return;
        }
        for (int i = 0; i < 32; i++) {
            final int a = curvePoint[i] & 0xff;
            final int b = N_BYTES[i] & 0xff;
            if (a < b) {
                return;
            }
            if (a > b) {
                throw new IllegalArgumentException("Curve point must be < N");
            }
        }
        // exactly equal to N is also out of bounds
        throw new IllegalArgumentException("Curve point must be < N");
    }

    private static boolean isAllZeros(final byte[] bytes) {
        for (final byte b : bytes) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the unsigned, fixed-width 32-byte big-endian representation of {@code v}.
     * {@code BigInteger.toByteArray()} prepends a sign byte for values whose high bit is set,
     * which is the case for the secp256k1 curve order {@code N}; this helper strips that.
     */
    private static byte[] toUnsignedFixed32(final BigInteger v) {
        final byte[] raw = v.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        if (raw.length == 33 && raw[0] == 0) {
            return Arrays.copyOfRange(raw, 1, 33);
        }
        if (raw.length < 32) {
            final byte[] out = new byte[32];
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
            return out;
        }
        throw new IllegalStateException("Value does not fit in 32 unsigned bytes: " + v);
    }
}
