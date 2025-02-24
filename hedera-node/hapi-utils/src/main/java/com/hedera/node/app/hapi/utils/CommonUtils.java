// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SignatureMap;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.pbj.runtime.ParseException;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import org.hiero.consensus.model.crypto.DigestType;

public final class CommonUtils {
    private CommonUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    private static String sha384HashTag = "SHA-384";

    public static String base64encode(final byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static Bytes extractTransactionBodyByteString(final Transaction transaction) throws ParseException {
        if (transaction.hasBody()) {
            return TransactionBody.PROTOBUF.toBytes(transaction.body());
        }
        final var signedTransactionBytes = transaction.signedTransactionBytes();
        if (signedTransactionBytes.length() > 0) {
            return SignedTransaction.PROTOBUF.parse(signedTransactionBytes).bodyBytes();
        }
        return transaction.bodyBytes();
    }

    public static byte[] extractTransactionBodyBytes(final Transaction transaction) throws ParseException {
        return extractTransactionBodyByteString(transaction).toByteArray();
    }

    /**
     * Extracts the {@link TransactionBody} from a {@link Transaction} and throws an unchecked exception if
     * the extraction fails.
     *
     * @param transaction the {@link Transaction} from which to extract the {@link TransactionBody}
     * @return the extracted {@link TransactionBody}
     */
    public static TransactionBody extractTransactionBodyUnchecked(final Transaction transaction) {
        try {
            return TransactionBody.PROTOBUF.parse(extractTransactionBodyByteString(transaction));
        } catch (ParseException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static TransactionBody extractTransactionBody(final Transaction transaction) throws ParseException {
        return TransactionBody.PROTOBUF.parse(extractTransactionBodyByteString(transaction));
    }

    public static SignatureMap extractSignatureMap(final Transaction transaction) throws ParseException {
        final var signedTransactionBytes = transaction.signedTransactionBytes();
        if (signedTransactionBytes.length() > 0) {
            return SignedTransaction.PROTOBUF.parse(signedTransactionBytes).sigMap();
        }
        return transaction.sigMapOrElse(SignatureMap.DEFAULT);
    }

    /**
     * Returns a {@link MessageDigest} instance for the SHA-384 algorithm, throwing an unchecked exception if the
     * algorithm is not found.
     * @return a {@link MessageDigest} instance for the SHA-384 algorithm
     */
    public static MessageDigest sha384DigestOrThrow() {
        try {
            return MessageDigest.getInstance(DigestType.SHA_384.algorithmName());
        } catch (final NoSuchAlgorithmException fatal) {
            throw new IllegalStateException(fatal);
        }
    }

    public static Bytes noThrowSha384HashOf(final Bytes bytes) {
        return Bytes.wrap(noThrowSha384HashOf(bytes.toByteArray()));
    }

    public static byte[] noThrowSha384HashOf(final byte[] byteArray) {
        try {
            return MessageDigest.getInstance(sha384HashTag).digest(byteArray);
        } catch (final NoSuchAlgorithmException fatal) {
            throw new IllegalStateException(fatal);
        }
    }

    public static boolean productWouldOverflow(final long multiplier, final long multiplicand) {
        if (multiplicand == 0) {
            return false;
        }
        final var maxMultiplier = Long.MAX_VALUE / multiplicand;
        return multiplier > maxMultiplier;
    }

    @VisibleForTesting
    static void setSha384HashTag(final String sha384HashTag) {
        CommonUtils.sha384HashTag = sha384HashTag;
    }

    /**
     * check TransactionBody and return the HederaFunctionality. This method was moved from MiscUtils.
     * NODE_STAKE_UPDATE is not checked in this method, since it is not a user transaction.
     *
     * @param txn the {@code TransactionBody}
     * @return one of HederaFunctionality
     */
    @NonNull
    public static HederaFunctionality functionOf(@NonNull final TransactionBody txn) throws UnknownHederaFunctionality {
        requireNonNull(txn);
        return HapiUtils.functionOf(txn);
    }

    /**
     * get the EVM address from the long number.
     *
     * @param shard the shard number
     * @param realm the realm number
     * @param num the input long number
     * @return evm address
     */
    public static byte[] asEvmAddress(final long shard, final long realm, final long num) {
        final byte[] evmAddress = new byte[20];
        arraycopy(Ints.toByteArray((int) shard), 0, evmAddress, 0, 4);
        arraycopy(Longs.toByteArray(realm), 0, evmAddress, 4, 8);
        arraycopy(Longs.toByteArray(num), 0, evmAddress, 12, 8);
        return evmAddress;
    }

    public static Instant timestampToInstant(final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    public static Instant pbjTimestampToInstant(final com.hedera.hapi.node.base.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }
}
