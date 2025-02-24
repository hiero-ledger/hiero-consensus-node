// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.fee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.FeeComponents;
import com.hedera.hapi.node.base.FeeData;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusDeleteTopicTransactionBody;
import com.hedera.hapi.node.consensus.ConsensusUpdateTopicTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class ConsensusServiceFeeBuilderTest {
    private static final Key A_KEY = Key.newBuilder()
            .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .build();
    private static final String MEMO = "This is a memo.";
    private static final Timestamp TIMESTAMP =
            Timestamp.newBuilder().seconds(100L).nanos(100).build();
    private static final AccountID ACCOUNT_A =
            AccountID.newBuilder().accountNum(3L).realmNum(0L).shardNum(0L).build();
    private static final Duration DURATION =
            Duration.newBuilder().seconds(1000L).build();
    private static final SigValueObj SIG_VALUE_OBJ = new SigValueObj(1, 1, 1);

    @Test
    void getConsensusCreateTopicFeeHappyPath() {
        final var txnBodyA = TransactionBody.newBuilder()
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.newBuilder()
                        .adminKey(A_KEY)
                        .submitKey(A_KEY)
                        .memo(MEMO)
                        .autoRenewAccount(ACCOUNT_A)
                        .autoRenewPeriod(DURATION)
                        .build())
                .build();
        final var txnBodyB = TransactionBody.newBuilder()
                .consensusCreateTopic(ConsensusCreateTopicTransactionBody.newBuilder()
                        .adminKey(A_KEY)
                        .submitKey(A_KEY)
                        .memo(MEMO)
                        .autoRenewAccount(ACCOUNT_A)
                        .build())
                .build();

        final var expectedA = getFeeData(1L, 188L, 1L, 4L, 3L, 62L);
        final var expectedB = getFeeData(1L, 188L, 1L, 4L, 3L, 6L);
        final var actualA = ConsensusServiceFeeBuilder.getConsensusCreateTopicFee(txnBodyA, SIG_VALUE_OBJ);
        final var actualB = ConsensusServiceFeeBuilder.getConsensusCreateTopicFee(txnBodyB, SIG_VALUE_OBJ);

        assertEquals(expectedA, actualA);
        assertEquals(expectedB, actualB);
    }

    @Test
    void getConsensusUpdateTopicFeeHappyPath() {
        final var txnBodyA = TransactionBody.newBuilder()
                .consensusUpdateTopic(ConsensusUpdateTopicTransactionBody.newBuilder()
                        .memo(MEMO)
                        .adminKey(A_KEY)
                        .expirationTime(TIMESTAMP)
                        .autoRenewPeriod(DURATION)
                        .build())
                .build();
        final var txnBodyB = TransactionBody.newBuilder()
                .consensusUpdateTopic(ConsensusUpdateTopicTransactionBody.newBuilder()
                        .memo(MEMO)
                        .adminKey(A_KEY)
                        .build())
                .build();

        final var expectedA = getFeeData(1L, 164L, 1L, 4L, 1L, 6L);
        final var expectedB = getFeeData(1L, 148L, 1L, 4L, 1L, 6L);
        final var actualA = ConsensusServiceFeeBuilder.getConsensusUpdateTopicFee(txnBodyA, 100L, SIG_VALUE_OBJ);
        final var actualB = ConsensusServiceFeeBuilder.getConsensusUpdateTopicFee(txnBodyB, 100L, SIG_VALUE_OBJ);

        assertEquals(expectedA, actualA);
        assertEquals(expectedB, actualB);
    }

    @Test
    void getUpdateTopicRbsIncreaseHappyPath() {
        final var bKey = Key.newBuilder()
                .ed25519(Bytes.wrap("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
                .keyList(KeyList.newBuilder().build())
                .build();
        final var emptyAccount =
                AccountID.newBuilder().accountNum(0L).realmNum(0L).shardNum(0L).build();
        final var txnBodyA = ConsensusUpdateTopicTransactionBody.newBuilder()
                .memo(MEMO)
                .adminKey(bKey)
                .submitKey(bKey)
                .autoRenewPeriod(DURATION)
                .autoRenewAccount(emptyAccount)
                .expirationTime(TIMESTAMP)
                .build();
        final var txnBodyB = ConsensusUpdateTopicTransactionBody.newBuilder()
                .autoRenewPeriod(DURATION)
                .build();

        final var actualA = ConsensusServiceFeeBuilder.getUpdateTopicRbsIncrease(
                TIMESTAMP, A_KEY, A_KEY, MEMO, true, TIMESTAMP, txnBodyA);
        final var actualB = ConsensusServiceFeeBuilder.getUpdateTopicRbsIncrease(
                TIMESTAMP, A_KEY, A_KEY, MEMO, true, TIMESTAMP, txnBodyB);

        assertEquals(0L, actualA);
        assertEquals(0L, actualB);
    }

    @Test
    void getConsensusDeleteTopicFeeHappyPath() {
        final var topicId =
                TopicID.newBuilder().topicNum(5L).realmNum(0L).shardNum(0L).build();
        final var txnBody = TransactionBody.newBuilder()
                .consensusDeleteTopic(ConsensusDeleteTopicTransactionBody.newBuilder()
                        .topicID(topicId)
                        .build())
                .build();

        final var expected = getFeeData(1L, 101L, 1L, 4L, 1L, 6L);
        final var actual = ConsensusServiceFeeBuilder.getConsensusDeleteTopicFee(txnBody, SIG_VALUE_OBJ);

        assertEquals(expected, actual);
    }

    @Test
    void helperMethodsWork() {
        final var baseTopicRamByteSize = 100;

        assertEquals(baseTopicRamByteSize, ConsensusServiceFeeBuilder.getTopicRamBytes(0));
        assertEquals(baseTopicRamByteSize + 20, ConsensusServiceFeeBuilder.getTopicRamBytes(20));

        final var actualWithAutoRenewAccountAndMemo =
                ConsensusServiceFeeBuilder.computeVariableSizedFieldsUsage(A_KEY, A_KEY, MEMO, true);
        final var actualWithoutAutRenewAccountAndMemo =
                ConsensusServiceFeeBuilder.computeVariableSizedFieldsUsage(A_KEY, A_KEY, null, false);

        assertEquals(103, actualWithAutoRenewAccountAndMemo);
        assertEquals(64, actualWithoutAutRenewAccountAndMemo);
    }

    private static final FeeData getFeeData(
            final long constant,
            final long bpt,
            final long vpt,
            final long bpr,
            final long rbhNetwork,
            final long rbhService) {
        return FeeData.newBuilder()
                .nodedata(FeeComponents.newBuilder()
                        .constant(constant)
                        .bpt(bpt)
                        .vpt(vpt)
                        .bpr(bpr)
                        .build())
                .networkdata(FeeComponents.newBuilder()
                        .constant(constant)
                        .bpt(bpt)
                        .vpt(vpt)
                        .rbh(rbhNetwork)
                        .build())
                .servicedata(FeeComponents.newBuilder()
                        .constant(constant)
                        .rbh(rbhService)
                        .build())
                .build();
    }
}
