// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Transaction;
import java.time.Clock;
import java.time.Instant;
import java.util.stream.Stream;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(CRYPTO)
public class CryptoCornerCasesSuite {
    private static final String NEW_PAYEE = "newPayee";

    private static Transaction removeTransactionBody(Transaction txn) {
        return txn.toBuilder()
                .setBodyBytes(Transaction.getDefaultInstance().getBodyBytes())
                .build();
    }

    @HapiTest
    final Stream<DynamicTest> invalidTransactionBody() {
        return hapiTest(cryptoCreate(NEW_PAYEE)
                .balance(10000L)
                .withProtoStructure(HapiSpecSetup.TxnProtoStructure.OLD) // Ensure legacy construction so
                // removeTransactionBody() works
                .withTxnTransform(CryptoCornerCasesSuite::removeTransactionBody)
                .hasPrecheckFrom(INVALID_TRANSACTION_BODY, INVALID_TRANSACTION));
    }

    private static Transaction replaceTxnNodeAccount(Transaction txn) {
        AccountID badNodeAccount = AccountID.newBuilder()
                .setAccountNum(2000)
                .setRealmNum(0)
                .setShardNum(0)
                .build();
        return TxnUtils.replaceTxnNodeAccount(txn, badNodeAccount);
    }

    @HapiTest
    final Stream<DynamicTest> invalidNodeAccount() {
        return hapiTest(cryptoCreate(NEW_PAYEE)
                .balance(10000L)
                .withTxnTransform(CryptoCornerCasesSuite::replaceTxnNodeAccount)
                .hasPrecheckFrom(INVALID_NODE_ACCOUNT, INVALID_TRANSACTION));
    }

    private static Transaction replaceTxnDuration(Transaction txn) {
        return TxnUtils.replaceTxnDuration(txn, -1L);
    }

    @HapiTest
    final Stream<DynamicTest> invalidTransactionDuration() {
        return hapiTest(cryptoCreate(NEW_PAYEE)
                .balance(10000L)
                .withTxnTransform(CryptoCornerCasesSuite::replaceTxnDuration)
                .hasPrecheckFrom(INVALID_TRANSACTION_DURATION, INVALID_TRANSACTION));
    }

    private static Transaction replaceTxnMemo(Transaction txn) {
        String newMemo = RandomStringUtils.randomAlphanumeric(120);
        return TxnUtils.replaceTxnMemo(txn, newMemo);
    }

    @HapiTest
    final Stream<DynamicTest> invalidTransactionMemoTooLong() {
        return hapiTest(cryptoCreate(NEW_PAYEE)
                .balance(10000L)
                .withTxnTransform(CryptoCornerCasesSuite::replaceTxnMemo)
                .hasPrecheckFrom(MEMO_TOO_LONG, INVALID_TRANSACTION));
    }

    private static Transaction replaceTxnPayerAccount(Transaction txn) {
        AccountID badPayerAccount = AccountID.newBuilder()
                .setShardNum(0)
                .setRealmNum(0)
                .setAccountNum(999999)
                .build();
        return TxnUtils.replaceTxnPayerAccount(txn, badPayerAccount);
    }

    @HapiTest
    final Stream<DynamicTest> invalidTransactionPayerAccountNotFound() {
        return hapiTest(cryptoCreate(NEW_PAYEE)
                .balance(10000L)
                .withTxnTransform(CryptoCornerCasesSuite::replaceTxnPayerAccount)
                .hasPrecheckFrom(PAYER_ACCOUNT_NOT_FOUND, INVALID_TRANSACTION));
    }

    private static Transaction replaceTxnStartTtime(Transaction txn) {
        long newStartTimeSecs = Instant.now(Clock.systemUTC()).getEpochSecond() + 100L;
        return TxnUtils.replaceTxnStartTime(txn, newStartTimeSecs, 0);
    }

    @HapiTest
    final Stream<DynamicTest> invalidTransactionStartTime() {
        return hapiTest(cryptoCreate(NEW_PAYEE)
                .balance(10000L)
                .withTxnTransform(CryptoCornerCasesSuite::replaceTxnStartTtime)
                .hasPrecheckFrom(INVALID_TRANSACTION_START, INVALID_TRANSACTION));
    }
}
