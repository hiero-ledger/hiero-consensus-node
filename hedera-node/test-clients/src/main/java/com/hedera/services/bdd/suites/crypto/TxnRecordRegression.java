// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.crypto;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.CRYPTO;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.ifNotEmbeddedTest;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECEIPT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNKNOWN;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.RepeatableHapiTest;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * ! WARNING - Requires a RecordCache TTL of 3s to pass !
 *
 * <p>Even with a 3s TTL, a number of these tests fail. FUTURE: revisit
 */
@Tag(CRYPTO)
public class TxnRecordRegression {
    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> recordsStillQueryableWithDeletedPayerId() {
        return hapiTest(
                cryptoCreate("toBeDeletedPayer"),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L))
                        .payingWith("toBeDeletedPayer")
                        .via("recordTxn"),
                cryptoDelete("toBeDeletedPayer"),
                getTxnRecord("recordTxn"));
    }

    @HapiTest
    final Stream<DynamicTest> returnsInvalidForUnspecifiedTxnId() {
        return hapiTest(getTxnRecord("").useDefaultTxnId().hasCostAnswerPrecheck(INVALID_ACCOUNT_ID));
    }

    @HapiTest
    final Stream<DynamicTest> recordNotFoundIfNotInPayerState() {
        return hapiTest(
                cryptoCreate("misc").via("success"),
                usableTxnIdNamed("rightAccountWrongId").payerId("misc"),
                getTxnRecord("rightAccountWrongId").hasAnswerOnlyPrecheck(RECORD_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> recordUnavailableBeforeConsensus() {
        return hapiTest(
                cryptoCreate("misc").via("success").balance(1_000L).deferStatusResolution(),
                // Running with embedded mode the previous transaction will often already be handled
                // and have a record available, so this is only interesting with a live network
                ifNotEmbeddedTest(getTxnRecord("success").hasAnswerOnlyPrecheck(RECORD_NOT_FOUND)));
    }

    @HapiTest
    final Stream<DynamicTest> recordUnavailableIfRejectedInPrecheck() {
        return hapiTest(
                cryptoCreate("misc").balance(1000L),
                usableTxnIdNamed("failingTxn").payerId("misc"),
                cryptoCreate("nope")
                        .payingWith("misc")
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE)
                        .txnId("failingTxn"),
                getTxnRecord("failingTxn").hasAnswerOnlyPrecheck(RECORD_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> getReceiptReturnsInvalidForUnspecifiedTxnId() {
        return hapiTest(getReceipt("").useDefaultTxnId().hasAnswerOnlyPrecheck(INVALID_TRANSACTION_ID));
    }

    @RepeatableHapiTest(NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION)
    final Stream<DynamicTest> receiptUnavailableAfterCacheTtl() {
        return hapiTest(
                // Every transaction in a repeatable spec reaches consensus one second apart,
                // and it uses a valid start offset of one second; hence this will reach
                // consensus at some time T with a valid start of T-1, and be purged after
                // any transaction that reaches consensus at T+180 or later
                cryptoCreate("misc").via("success").balance(1_000L),
                // Sleep until T+179
                sleepFor(179_000L),
                // Run a transaction that will reach consensus at T+180 to purge receipts
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1L)),
                getReceipt("success").hasAnswerOnlyPrecheck(RECEIPT_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> receiptUnknownBeforeConsensus() {
        return hapiTest(
                cryptoCreate("misc").via("success").balance(1_000L).deferStatusResolution(),
                getReceipt("success").hasPriorityStatus(UNKNOWN));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> receiptAvailableWithinCacheTtl() {
        return hapiTest(
                cryptoCreate("misc").via("success").balance(1_000L),
                getReceipt("success").hasPriorityStatus(SUCCESS));
    }

    @HapiTest
    final Stream<DynamicTest> receiptUnavailableIfRejectedInPrecheck() {
        return hapiTest(
                cryptoCreate("misc").balance(1_000L),
                cryptoCreate("nope")
                        .payingWith("misc")
                        .hasPrecheck(INSUFFICIENT_PAYER_BALANCE)
                        .via("failingTxn"),
                getReceipt("failingTxn").hasAnswerOnlyPrecheck(RECEIPT_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> receiptNotFoundOnUnknownTransactionID() {
        return hapiTest(withOpContext((spec, ctxLog) -> allRunFor(
                spec,
                getReceipt(TransactionID.newBuilder()
                                .setAccountID(AccountID.newBuilder()
                                        .setAccountNum(Long.MAX_VALUE)
                                        .build())
                                .build())
                        .hasAnswerOnlyPrecheck(INVALID_TRANSACTION_ID),
                getReceipt(TransactionID.newBuilder()
                                .setTransactionValidStart(Timestamp.newBuilder()
                                        .setSeconds(Long.MAX_VALUE)
                                        .setNanos(Integer.MAX_VALUE)
                                        .build())
                                .build())
                        .hasAnswerOnlyPrecheck(INVALID_TRANSACTION_ID),
                getReceipt(TransactionID.newBuilder()
                                .setAccountID(AccountID.newBuilder()
                                        .setAccountNum(Long.MAX_VALUE)
                                        .build())
                                .setTransactionValidStart(Timestamp.newBuilder()
                                        .setSeconds(Long.MAX_VALUE)
                                        .setNanos(Integer.MAX_VALUE)
                                        .build())
                                .build())
                        .hasAnswerOnlyPrecheck(RECEIPT_NOT_FOUND))));
    }
}
