// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils;

import static com.hedera.node.app.hapi.utils.ByteStringUtils.unwrapUnsafelyIfPossible;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.toPbj;
import static java.lang.System.arraycopy;
import static java.util.Objects.requireNonNull;
import static org.hiero.base.crypto.Cryptography.NULL_HASH;

import com.google.common.primitives.Longs;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignedTransaction;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionOrBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import org.hiero.base.crypto.DigestType;

public final class CommonUtils {
    private CommonUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static String base64encode(final byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static ByteString extractTransactionBodyByteString(final TransactionOrBuilder transaction)
            throws InvalidProtocolBufferException {
        if (transaction.hasBody()) {
            return transaction.getBody().toByteString();
        }
        final var signedTransactionBytes = transaction.getSignedTransactionBytes();
        if (!signedTransactionBytes.isEmpty()) {
            return SignedTransaction.parseFrom(signedTransactionBytes).getBodyBytes();
        }
        return transaction.getBodyBytes();
    }

    public static byte[] extractTransactionBodyBytes(final TransactionOrBuilder transaction)
            throws InvalidProtocolBufferException {
        return unwrapUnsafelyIfPossible(extractTransactionBodyByteString(transaction));
    }

    /**
     * Extracts the {@link TransactionBody} from a {@link TransactionOrBuilder} and throws an unchecked exception if
     * the extraction fails.
     *
     * @param transaction the {@link TransactionOrBuilder} from which to extract the {@link TransactionBody}
     * @return the extracted {@link TransactionBody}
     */
    public static TransactionBody extractTransactionBodyUnchecked(final TransactionOrBuilder transaction) {
        try {
            return TransactionBody.parseFrom(extractTransactionBodyByteString(transaction));
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public static TransactionBody extractTransactionBody(final TransactionOrBuilder transaction)
            throws InvalidProtocolBufferException {
        return TransactionBody.parseFrom(extractTransactionBodyByteString(transaction));
    }

    public static SignatureMap extractSignatureMap(final TransactionOrBuilder transaction)
            throws InvalidProtocolBufferException {
        final var signedTransactionBytes = transaction.getSignedTransactionBytes();
        if (!signedTransactionBytes.isEmpty()) {
            return SignedTransaction.parseFrom(signedTransactionBytes).getSigMap();
        }
        return transaction.getSigMap();
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

    // SHA-384 hash functions with the default-provided message digest
    // ** BEGIN Bytes Variants **
    // (FUTURE) Rename since 'no throw' is confusing
    public static Bytes noThrowSha384HashOf(@NonNull final Bytes bytes) {
        final var digest = sha384DigestOrThrow();
        return hashOfAll(digest, bytes);
    }

    public static Bytes sha384HashOfAll(final Bytes... allBytes) {
        final var digest = sha384DigestOrThrow();
        return hashOfAll(digest, allBytes);
    }

    public static Bytes hashOfAll(@NonNull final MessageDigest digest, @NonNull final Bytes... allBytes) {
        requireNonNull(digest);
        requireNonNull(allBytes);
        for (final var bytes : allBytes) {
            bytes.writeTo(digest);
        }
        return Bytes.wrap(digest.digest());
    }

    // ** BEGIN byte[] Variants **
    // (FUTURE) Rename since 'no throw' is confusing
    public static byte[] noThrowSha384HashOf(final byte[] byteArray) {
        requireNonNull(byteArray);

        final var digest = sha384DigestOrThrow();
        return digest.digest(byteArray);
    }

    public static Bytes sha384HashOfAll(final byte[]... bytes) {
        return Bytes.wrap(sha384HashOf(bytes));
    }

    public static byte[] sha384HashOf(final byte[]... bytes) {
        return hashOfAll(sha384DigestOrThrow(), bytes);
    }

    public static Bytes sha384HashOf(
            @NonNull final Bytes first, @NonNull final Bytes second, @NonNull final byte[] third) {
        requireNonNull(first);
        requireNonNull(second);
        requireNonNull(third);

        final var digest = sha384DigestOrThrow();
        first.writeTo(digest);
        second.writeTo(digest);
        return Bytes.wrap(digest.digest(third));
    }

    public static byte[] hashOfAll(@NonNull final MessageDigest digest, @NonNull final byte[]... bytes) {
        requireNonNull(digest);
        requireNonNull(bytes);
        for (final var member : bytes) {
            digest.update(member);
        }
        return digest.digest();
    }

    public static boolean productWouldOverflow(final long multiplier, final long multiplicand) {
        if (multiplicand == 0) {
            return false;
        }
        final var maxMultiplier = Long.MAX_VALUE / multiplicand;
        return multiplier > maxMultiplier;
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
        return fromPbj(HapiUtils.functionOf(toPbj(txn)));
    }

    /**
     * get the EVM address from the long number.
     *
     * @param num the input long number
     * @return evm address
     */
    public static byte[] asEvmAddress(final long num) {
        final byte[] evmAddress = new byte[20];
        arraycopy(Longs.toByteArray(num), 0, evmAddress, 12, 8);
        return evmAddress;
    }

    public static Instant timestampToInstant(final Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    public static Instant pbjTimestampToInstant(final com.hedera.hapi.node.base.Timestamp timestamp) {
        return Instant.ofEpochSecond(timestamp.seconds(), timestamp.nanos());
    }

    /**
     * Converts a long-zero address to a PBJ {@link ScheduleID} using the address as the entity number
     * @param shard the shard of the Hedera network
     * @param realm the realm of the Hedera network
     * @param address the long-zero address
     * @return the PBJ {@link ScheduleID}
     */
    public static com.hederahashgraph.api.proto.java.ScheduleID asScheduleId(
            final long shard, final long realm, @NonNull final com.esaulpaugh.headlong.abi.Address address) {
        return com.hederahashgraph.api.proto.java.ScheduleID.newBuilder()
                .setShardNum(shard)
                .setRealmNum(realm)
                .setScheduleNum(address.value().longValueExact())
                .build();
    }

    /**
     * Adds two longs, returning {@link Long#MAX_VALUE} or {@link Long#MIN_VALUE} if overflow or underflow occurs.
     * @param addendA the first addend
     * @param addendB the second addend
     * @return the sum, or {@link Long#MAX_VALUE} or {@link Long#MIN_VALUE} if overflow or underflow occurs
     */
    public static long clampedAdd(final long addendA, final long addendB) {
        try {
            return Math.addExact(addendA, addendB);
        } catch (final ArithmeticException ae) {
            return addendA > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    public static long clampedMultiply(final long a, final long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException ignore) {
            return Long.signum(a) == Long.signum(b) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    /**
     * Returns the given hash if it is non-null and non-empty; otherwise, returns {@code NULL_HASH}
     * @param maybeHash the possibly null or empty hash
     * @return the given hash or {@code NULL_HASH} if the given hash is null or empty
     */
    public static Bytes inputOrNullHash(@Nullable final Bytes maybeHash) {
        return (maybeHash != null && maybeHash.length() > 0) ? maybeHash : NULL_HASH.getBytes();
    }
}
