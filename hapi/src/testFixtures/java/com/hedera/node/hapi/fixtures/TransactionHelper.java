// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.hapi.fixtures;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoQuery;
import com.hedera.hapi.node.consensus.ConsensusSubmitMessageTransactionBody;
import com.hedera.hapi.node.network.NetworkGetExecutionTimeQuery;
import com.hedera.hapi.node.token.CryptoGetLiveHashQuery;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody.Builder;
import com.hedera.hapi.node.transaction.UncheckedSubmitBody;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.pbj.runtime.io.stream.WritableStreamingData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A helper class that can be used to create different types of transactions and their associated
 * configurations. This class can be reused in unit and integration tests.
 */
public interface TransactionHelper {
    // The date of the Open Access announcement 🎉
    long START_TIME = 1568692800 / 1000;

    default AccountID account(long number) {
        return account(0, 0, number);
    }

    default AccountID account(String account) {
        final var parts = account.split("\\.");
        return account(Long.parseLong(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    }

    default AccountID account(long shard, long realm, long num) {
        return AccountID.newBuilder()
                .shardNum(shard)
                .realmNum(realm)
                .accountNum(num)
                .build();
    }

    /**
     * Creates and returns a new ed25519 {@link Key} based on the given UTF-8 string.
     *
     * @param utf8 A non-null string
     * @return an ed25519 {@link Key}
     */
    default Key ed25519(@NonNull final String utf8) {
        return new Key.Builder().ed25519(Bytes.wrap(utf8)).build();
    }

    /**
     * Creates and returns a new ECDSA secp256k1 {@link Key} based on the given UTF-8 string.
     *
     * @param utf8 A non-null string
     * @return an ECDSA {@link Key}
     */
    default Key ecdsa(@NonNull final String utf8) {
        return new Key.Builder().ecdsaSecp256k1(Bytes.wrap(utf8)).build();
    }

    /**
     * Creates and returns a threshold key based on the given threshold and keys
     *
     * @param threshold The threshold
     * @param keys The set of keys
     * @return A {@link Key} based on a threshold key list
     */
    default Key thresholdKey(int threshold, Key... keys) {
        return new Key.Builder()
                .thresholdKey(new ThresholdKey.Builder()
                        .threshold(threshold)
                        .keys(new KeyList.Builder().keys(Arrays.asList(keys)).build())
                        .build())
                .build();
    }

    default Transaction transaction(AccountID payer, long time, Consumer<Builder> txBodyBuilder) {
        final var txId = TransactionID.newBuilder()
                .transactionValidStart(
                        Timestamp.newBuilder().seconds(time / 1000).build())
                .accountID(payer)
                .build();

        final var bodyBuilder =
                TransactionBody.newBuilder().transactionID(txId).memo("A Memo").transactionFee(1_000_000);
        txBodyBuilder.accept(bodyBuilder);
        final var body = bodyBuilder.build();

        final var signedTx = SignedTransaction.newBuilder()
                .bodyBytes(asBytes(body, TransactionBody.PROTOBUF))
                .build();

        return Transaction.newBuilder()
                .signedTransactionBytes(asBytes(signedTx, SignedTransaction.PROTOBUF))
                .build();
    }

    default ConsensusSubmitMessageTransactionBody.Builder consensusSubmitMessageBuilder(int topicId, String msg) {
        return ConsensusSubmitMessageTransactionBody.newBuilder()
                .topicID(TopicID.newBuilder().topicNum(topicId).build())
                .message(Bytes.wrap(msg));
    }

    default ConsensusCreateTopicTransactionBody.Builder consensusCreateTopicBuilder(String memo) {
        return ConsensusCreateTopicTransactionBody.newBuilder().memo(memo);
    }

    default UncheckedSubmitBody.Builder uncheckedSubmitBuilder() {
        return UncheckedSubmitBody.newBuilder().transactionBytes(Bytes.EMPTY);
    }

    default Transaction consensusSubmitMessageTransaction(
            @NonNull final AccountID payer, final long time, final int topicId, final String msg) {
        final var data = consensusSubmitMessageBuilder(topicId, msg);
        return transaction(payer, time, b -> b.consensusSubmitMessage(data));
    }

    default Transaction consensusCreateTopicTransaction(
            @NonNull final AccountID payer, final long time, final String memo) {
        final var data = ConsensusCreateTopicTransactionBody.newBuilder().memo(memo);
        return transaction(payer, time, b -> b.consensusCreateTopic(data));
    }

    default Transaction uncheckedSubmitTransaction(@NonNull final AccountID payer, final long time) {
        final var data = UncheckedSubmitBody.newBuilder().transactionBytes(Bytes.EMPTY);
        return transaction(payer, time, b -> b.uncheckedSubmit(data));
    }

    default Transaction uncheckedSubmitTransaction(
            @NonNull final AccountID payer, final long time, @NonNull final Transaction tx) {
        final var data = UncheckedSubmitBody.newBuilder().transactionBytes(asBytes(tx, Transaction.PROTOBUF));
        return transaction(payer, time, b -> b.uncheckedSubmit(data));
    }

    default Query getTopicInfoQuery(int topicId) {
        final var data = ConsensusGetTopicInfoQuery.newBuilder()
                .topicID(TopicID.newBuilder().topicNum(topicId).build())
                .build();

        return Query.newBuilder().consensusGetTopicInfo(data).build();
    }

    default Query getExecutionTimeQuery() {
        final var data = NetworkGetExecutionTimeQuery.newBuilder().build();

        return Query.newBuilder().networkGetExecutionTime(data).build();
    }

    default Query getLiveHashQuery() {
        final var data = CryptoGetLiveHashQuery.newBuilder().build();

        return Query.newBuilder().cryptoGetLiveHash(data).build();
    }

    default <T> Bytes asBytes(T tx, Codec<T> codec) {
        try {
            final var dataOut = byteArrayDataOutput();
            codec.write(tx, dataOut);
            return dataOut.getBytes();
        } catch (IOException e) {
            throw new AssertionError("Failed to get transaction bytes", e);
        }
    }

    default <T> byte[] asByteArray(T tx, Codec<T> codec) {
        try {
            final var dataOut = byteArrayDataOutput();
            codec.write(tx, dataOut);
            return dataOut.getByteArray();
        } catch (IOException e) {
            throw new AssertionError("Failed to get transaction bytes", e);
        }
    }

    private static ByteArrayDataOutput byteArrayDataOutput() {
        final var out = new ByteArrayOutputStream();
        return new ByteArrayDataOutput(out);
    }

    final class ByteArrayDataOutput extends WritableStreamingData {
        private final ByteArrayOutputStream out;

        public ByteArrayDataOutput(ByteArrayOutputStream out) {
            super(out);
            this.out = out;
        }

        public Bytes getBytes() {
            return Bytes.wrap(out.toByteArray());
        }

        public byte[] getByteArray() {
            return out.toByteArray();
        }
    }
}
