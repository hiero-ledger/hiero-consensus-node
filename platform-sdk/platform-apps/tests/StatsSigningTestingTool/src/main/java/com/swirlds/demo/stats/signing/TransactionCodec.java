// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.stats.signing;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.demo.stats.signing.algorithms.ECSecP256K1Algorithm;
import com.swirlds.demo.stats.signing.algorithms.SigningAlgorithm;
import com.swirlds.demo.stats.signing.algorithms.X25519SigningAlgorithm;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.crypto.TransactionSignature;

/**
 * The core transaction encoder and decoder implementation. See below for the binary transaction format specification.
 * <p>
 * Transaction Structure:
 * ---------------------------------------------------------------------------------------------------------------------
 * | 1 byte | 8 bytes | 1 byte | 1 byte   | 4 bytes   | pklen bytes | 4 bytes | siglen bytes | 4 bytes | datalen bytes |
 * |--------|---------|--------|----------|-----------|-------------|---------|--------------|---------|---------------|
 * | marker | id      | signed | sigAlgId | pklen     | pk          |  siglen | sig          | datalen | data          |
 * ---------------------------------------------------------------------------------------------------------------------
 */
final class TransactionCodec {

    public static final byte APPLICATION_TRANSACTION_MARKER = 1;
    public static final byte NO_ALGORITHM_PRESENT = -1;

    private static final int PREAMBLE_SIZE = Long.BYTES + (Byte.BYTES * 2);

    private static final int TX_PREAMBLE_ID_OFFSET = 0;
    private static final int TX_PREAMBLE_SIGNED_OFFSET = 8;
    private static final int TX_PREAMBLE_SIG_ALG_ID_OFFSET = 9;
    private static final int TX_PREAMBLE_PK_LEN_OFFSET = 10;

    private static final Map<Byte, SigningAlgorithm> activeAlgorithms = new HashMap<>();
    private static final ECSecP256K1Algorithm EC_SEC_P_256_K_1_ALGORITHM = new ECSecP256K1Algorithm();
    private static final X25519SigningAlgorithm X_25519_SIGNING_ALGORITHM = new X25519SigningAlgorithm();

    static {
        activeAlgorithms.put(EC_SEC_P_256_K_1_ALGORITHM.getId(), EC_SEC_P_256_K_1_ALGORITHM);
        activeAlgorithms.put(X_25519_SIGNING_ALGORITHM.getId(), X_25519_SIGNING_ALGORITHM);

        X_25519_SIGNING_ALGORITHM.tryAcquirePrimitives();
        EC_SEC_P_256_K_1_ALGORITHM.tryAcquirePrimitives();
    }

    public static int bufferSize(final SigningAlgorithm algorithm, final int dataLength) {
        return overheadSize(algorithm) + Math.abs(dataLength);
    }

    public static int overheadSize(final SigningAlgorithm algorithm) {
        int size = PREAMBLE_SIZE;

        if (algorithm != null && algorithm.isAvailable()) {
            size += algorithm.getPublicKeyLength();
            size += algorithm.getSignatureLength();
        }

        size += Integer.BYTES * 3;

        return size;
    }

    public static byte[] encode(
            final SigningAlgorithm algorithm, final long transactionId, final byte[] signature, final byte[] data) {
        final ByteBuffer buffer = ByteBuffer.allocate(1 + bufferSize(algorithm, (data != null) ? data.length : 0));
        final boolean signed =
                algorithm != null && algorithm.isAvailable() && signature != null && signature.length > 0;

        // Add a marker byte in the very beginning to indicate the start of an application transaction. This is used
        // to later differentiate between application transactions and system transactions.
        buffer.put(APPLICATION_TRANSACTION_MARKER)
                .putLong(transactionId)
                .put((signed) ? (byte) 1 : 0)
                .put((signed) ? algorithm.getId() : NO_ALGORITHM_PRESENT)
                .putInt((signed) ? algorithm.getPublicKeyLength() : 0);

        if (signed) {
            buffer.put(algorithm.getPublicKey());
        }

        buffer.putInt((signed) ? signature.length : 0);

        if (signed) {
            buffer.put(signature);
        }

        buffer.putInt((data != null) ? data.length : 0);

        if (data != null) {
            buffer.put(data);
        }

        return buffer.array();
    }

    public static boolean txIsSigned(final Bytes tx) {
        if (tx == null) {
            throw new IllegalArgumentException("Invalid Transaction: Null Reference");
        }

        if (tx.length() <= TX_PREAMBLE_SIGNED_OFFSET) {
            throw new IllegalArgumentException("Invalid Transaction: Truncated Preamble");
        }

        return tx.getByte(TX_PREAMBLE_SIGNED_OFFSET) == 1;
    }

    public static long txId(final Bytes tx) {
        if (tx == null) {
            throw new IllegalArgumentException("Invalid Transaction: Null Reference");
        }

        if (tx.length() <= PREAMBLE_SIZE) {
            throw new IllegalArgumentException("Invalid Transaction: Truncated Preamble");
        }

        return tx.getLong(0);
    }

    public static TransactionSignature extractSignature(final Bytes tx) {
        if (tx == null) {
            throw new IllegalArgumentException("Invalid Transaction: Null Reference");
        }

        if (tx.length() <= PREAMBLE_SIZE) {
            throw new IllegalArgumentException("Invalid Transaction: Truncated Preamble");
        }

        final boolean signed = tx.getByte(TX_PREAMBLE_SIGNED_OFFSET) == 1;

        if (!signed) {
            throw new IllegalStateException("Invalid Signature: Transaction Is Unsigned");
        }

        final byte[] txBytes = tx.toByteArray();
        final ByteBuffer wrapper = ByteBuffer.wrap(txBytes).position(TX_PREAMBLE_SIG_ALG_ID_OFFSET);
        final byte algorithmId = wrapper.get();
        final SigningAlgorithm algorithm = activeAlgorithms.get(algorithmId);
        final SignatureType signatureType = (algorithm != null) ? algorithm.getSignatureType() : SignatureType.ED25519;

        return (signatureType == SignatureType.ECDSA_SECP256K1)
                ? readEcdsaSignature(wrapper, algorithm, signatureType)
                : readStandardSignature(wrapper, txBytes, signatureType);
    }

    private static TransactionSignature readStandardSignature(
            final ByteBuffer wrapper, final byte[] tx, final SignatureType signatureType) {
        final int pkLen = wrapper.getInt();
        final int pkOffset = wrapper.position();
        wrapper.position(pkOffset + pkLen);

        final int sigLen = wrapper.getInt();
        final int sigOffset = wrapper.position();
        wrapper.position(sigOffset + sigLen);

        final int dataLen = wrapper.getInt();
        final int dataOffset = wrapper.position();

        return new TransactionSignature(
                Bytes.wrap(tx, dataOffset, dataLen),
                Bytes.wrap(tx, pkOffset, pkLen),
                Bytes.wrap(tx, sigOffset, sigLen),
                signatureType);
    }

    private static TransactionSignature readEcdsaSignature(
            final ByteBuffer wrapper, final SigningAlgorithm algorithm, final SignatureType signatureType) {
        final int pkLen = wrapper.getInt();
        final byte[] pk = new byte[pkLen];
        wrapper.get(pk);

        final int sigLen = wrapper.getInt();
        final byte[] sig = new byte[sigLen];
        wrapper.get(sig);

        final int dataLen = wrapper.getInt();
        final byte[] data = new byte[dataLen];
        wrapper.get(data);

        final byte[] dataHash = algorithm.hash(data, 0, data.length);
        final ByteBuffer sigPayload = ByteBuffer.allocate(pkLen + sigLen + dataHash.length);
        sigPayload.put(pk).put(sig).put(dataHash);

        return new TransactionSignature(Bytes.wrap(dataHash), Bytes.wrap(pk), Bytes.wrap(sig), signatureType);
    }
}
