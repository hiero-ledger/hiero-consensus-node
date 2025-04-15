// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_KEY_SET_ON_NON_INNER_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_CONTAINS_DUPLICATES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_BATCH_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;

@HapiTestLifecycle
public class AtomicBatchNegativeTest {

    @Nested
    @DisplayName("Order and Execution - NEGATIVE")
    class OrderAndExecutionNegative {

        @HapiTest
        @DisplayName("Batch containing schedule sign and failing inner transaction")
        // BATCH_56
        public Stream<DynamicTest> scheduleSignAndFailingInnerTxn() {
            final var batchOperator = "batchOperator";
            final var sender = "sender";
            final var receiver = "receiver";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(FIVE_HBARS),
                    cryptoCreate(sender).balance(ONE_HBAR),
                    cryptoCreate(receiver).balance(0L),

                    // create a schedule
                    scheduleCreate("schedule", cryptoTransfer(tinyBarsFromTo(sender, receiver, 1)))
                            .waitForExpiry(false),
                    atomicBatch(
                                    // sign the schedule
                                    scheduleSign("schedule").payingWith(sender).batchKey(batchOperator),
                                    // failing transfer
                                    cryptoTransfer(tinyBarsFromTo(sender, receiver, ONE_HUNDRED_HBARS))
                                            .batchKey(batchOperator))
                            .payingWith(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate executed schedule was reverted
                    getScheduleInfo("schedule").isNotExecuted(),
                    getAccountBalance(receiver).hasTinyBars(0L));
        }

        @HapiTest
        @DisplayName("Batch transactions reverts on failure")
        // BATCH_57
        public Stream<DynamicTest> batchTransactionsRevertsOnFailure() {
            final var sender = "sender";
            final var oldKey = "oldKey";
            final var newKey = "newKey";
            return hapiTest(
                    newKeyNamed(oldKey),
                    cryptoCreate(sender).key(oldKey).balance(FIVE_HBARS),
                    newKeyNamed(newKey),
                    atomicBatch(
                                    cryptoUpdate(sender).key(newKey).batchKey(sender),
                                    cryptoDelete(sender).batchKey(sender),
                                    cryptoTransfer(tinyBarsFromTo(GENESIS, sender, 1))
                                            .batchKey(sender))
                            .payingWith(sender)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate the account update and delete were reverted
                    withOpContext((spec, opLog) -> {
                        final var expectedKey = spec.registry().getKey(oldKey);
                        final var accountQuery = getAccountDetails(sender)
                                .logged()
                                .has(accountDetailsWith().key(expectedKey));
                        allRunFor(spec, accountQuery);
                    }));
        }

        @HapiTest
        @DisplayName("Multi batch with 2 inner txns fails")
        public Stream<DynamicTest> multiBatchFail() {
            final var batchOperator = "batchOperator";
            final var innerTxnPayer = "innerPayer";
            final var innerTxnId1 = "innerId1";
            final var innerTxnId2 = "innerId2";
            final var account1 = "foo1";
            final var account2 = "foo2";
            final var atomicTxn = "atomicTxn";

            final var innerTxn1 = cryptoCreate(account1)
                    .balance(ONE_HBAR)
                    .txnId(innerTxnId1)
                    .batchKey(batchOperator)
                    .payingWith(innerTxnPayer);
            final var innerTxn2 = cryptoCreate(account2)
                    .balance(ONE_MILLION_HBARS)
                    .txnId(innerTxnId2)
                    .batchKey(batchOperator)
                    .payingWith(innerTxnPayer);

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HBAR),
                    cryptoCreate(innerTxnPayer).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(innerTxnId1).payerId(innerTxnPayer),
                    usableTxnIdNamed(innerTxnId2).payerId(innerTxnPayer),
                    atomicBatch(innerTxn1, innerTxn2)
                            .via(atomicTxn)
                            .payingWith(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    getTxnRecord(atomicTxn).logged(),
                    getTxnRecord(innerTxnId1).assertingNothingAboutHashes().logged(),
                    getTxnRecord(innerTxnId2).assertingNothingAboutHashes().logged());
        }
    }

    @Nested
    @DisplayName("Batch Constraints - NEGATIVE")
    class BatchConstraintsNegative {

        @HapiTest
        @DisplayName("Batch fails with inner txn missing DEFAULT_PAYER key signature")
        public Stream<DynamicTest> missingInnerTxnPayerSignatureFails() {
            final var batchOperator = "batchOperator";
            final var innerTxnPayer = "innerPayer";
            final var innerTxnId = "innerId";
            // crete inner txn with innerTxnPayer, but sign only with DEFAULT_PAYER
            final var innerTxn = cryptoCreate("foo")
                    .balance(ONE_HBAR)
                    .txnId(innerTxnId)
                    .batchKey(batchOperator)
                    .payingWith(innerTxnPayer)
                    .signedBy(DEFAULT_PAYER);

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HBAR),
                    cryptoCreate(innerTxnPayer).balance(ONE_HBAR),
                    usableTxnIdNamed(innerTxnId).payerId(innerTxnPayer),
                    // Since the inner txn is signed by DEFAULT_PAYER, it should fail
                    atomicBatch(innerTxn).payingWith(batchOperator).hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName(value = "Batch with invalid txn start fails")
        public Stream<DynamicTest> invalidTransactionStartFailed() {
            final var batchOperator = "batchOperator";
            final var innerTxnPayer = "innerPayer";
            final var innerTxnId1 = "innerId1";
            final var account1 = "foo1";

            final var innerTxn1 = cryptoCreate(account1)
                    .balance(ONE_HBAR)
                    .txnId(innerTxnId1)
                    .batchKey(batchOperator)
                    .payingWith(innerTxnPayer);
            final var validStart = Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond() + 1000)
                    .setNanos(1)
                    .build();

            return hapiTest(
                    cryptoCreate(innerTxnPayer).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(innerTxnId1).payerId(innerTxnPayer).validStart(validStart),
                    cryptoCreate(batchOperator).balance(ONE_HBAR),
                    atomicBatch(innerTxn1).payingWith(batchOperator).hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Batch with already used transaction ID should fail")
        public Stream<DynamicTest> duplicatedBatchInnerTransactionsFail() {
            final var batchOperator = "batchOperator";
            final var innerTxnPayer = "innerPayer";
            final var innerTxnId1 = "innerId1";
            final var innerTxnId2 = "innerId2";
            final var account1 = "foo1";
            final var account2 = "foo2";

            final var innerTxn1 = cryptoCreate(account1)
                    .balance(ONE_HBAR)
                    .txnId(innerTxnId1)
                    .batchKey(batchOperator)
                    .payingWith(innerTxnPayer);
            final var innerTxn2 = cryptoCreate(account2)
                    .balance(ONE_HBAR)
                    .txnId(innerTxnId2)
                    .batchKey(batchOperator)
                    .payingWith(innerTxnPayer);

            return hapiTest(
                    cryptoCreate(innerTxnPayer).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(innerTxnId1).payerId(innerTxnPayer),
                    usableTxnIdNamed(innerTxnId2).payerId(innerTxnPayer),
                    cryptoCreate(batchOperator)
                            .txnId(innerTxnId1)
                            .payingWith(innerTxnPayer)
                            .balance(ONE_HBAR),
                    atomicBatch(innerTxn1, innerTxn2)
                            .payingWith(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    atomicBatch(innerTxn2).payingWith(batchOperator),
                    atomicBatch(innerTxn2).payingWith(batchOperator).hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Empty batch should fail")
        // BATCH_37
        public Stream<DynamicTest> submitEmptyBatch() {
            return hapiTest(atomicBatch().hasPrecheck(BATCH_LIST_EMPTY));
        }

        @HapiTest
        @DisplayName("Batch with invalid duration should fail")
        // BATCH_39
        public Stream<DynamicTest> batchWithInvalidDurationShouldFail() {
            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    atomicBatch(cryptoCreate("foo").batchKey("batchOperator"))
                            .validDurationSecs(-5)
                            .payingWith("batchOperator")
                            .hasPrecheck(INVALID_TRANSACTION_DURATION));
        }

        @HapiTest
        @DisplayName("Batch containing inner txn with invalid duration should fail")
        // BATCH_41
        public Stream<DynamicTest> innerTxnWithInvalidDuration() {
            final var innerId = "innerId";
            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    usableTxnIdNamed(innerId).payerId("batchOperator"),
                    atomicBatch(cryptoCreate(innerId)
                                    .txnId(innerId)
                                    .validDurationSecs(-1)
                                    .batchKey("batchOperator"))
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    getTxnRecord(innerId)
                            .assertingNothingAboutHashes()
                            .hasPriority(recordWith().status((INVALID_TRANSACTION_DURATION))));
        }

        @HapiTest
        @DisplayName("Submit same batch twice should fail")
        // BATCH_42 BATCH_43
        public Stream<DynamicTest> submitSameBatch() {

            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    usableTxnIdNamed("successfulBatch").payerId("batchOperator"),
                    usableTxnIdNamed("failingBatch").payerId("batchOperator"),
                    cryptoCreate("sender").balance(0L),
                    cryptoCreate("receiver"),

                    // successful batch duplication
                    atomicBatch(cryptoCreate("foo").batchKey("batchOperator"))
                            .txnId("successfulBatch")
                            .payingWith("batchOperator"),
                    atomicBatch(cryptoCreate("foo").batchKey("batchOperator"))
                            .txnId("successfulBatch")
                            .payingWith("batchOperator")
                            .hasPrecheck(DUPLICATE_TRANSACTION),

                    // failing batch duplication
                    atomicBatch(cryptoTransfer(movingHbar(10L).between("sender", "receiver"))
                                    .batchKey("batchOperator")
                                    .signedByPayerAnd("sender"))
                            .txnId("failingBatch")
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    atomicBatch(cryptoTransfer(movingHbar(10L).between("sender", "receiver"))
                                    .batchKey("batchOperator")
                                    .signedByPayerAnd("sender"))
                            .txnId("failingBatch")
                            .payingWith("batchOperator")
                            .hasPrecheck(DUPLICATE_TRANSACTION));
        }

        @HapiTest
        @DisplayName("Submit batch with duplicated inner txn should fail")
        // BATCH_44
        public Stream<DynamicTest> duplicatedInnerTxn() {
            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    usableTxnIdNamed("innerId").payerId("batchOperator"),
                    withOpContext((spec, opLog) -> {
                        var txn = cryptoCreate("foo")
                                .setNode("0.0.0")
                                .txnId("innerId")
                                .batchKey("batchOperator")
                                .payingWith("batchOperator");
                        var batchOp =
                                // add same inner transaction twice
                                atomicBatch(txn, txn)
                                        .payingWith("batchOperator")
                                        .hasPrecheck(BATCH_LIST_CONTAINS_DUPLICATES);
                        allRunFor(spec, batchOp);
                    }));
        }

        @LeakyHapiTest(requirement = {THROTTLE_OVERRIDES})
        @DisplayName("Bach contract call with more than the TPS limit")
        //  BATCH_47
        public Stream<DynamicTest> contractCallMoreThanTPSLimit() {
            final var batchOperator = "batchOperator";
            final var contract = "CalldataSize";
            final var function = "callme";
            final var payload = new byte[100];
            final var payer = "payer";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    cryptoCreate(payer).balance(ONE_HBAR),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    overridingThrottles("testSystemFiles/artificial-limits.json"),
                    // create batch with 6 contract calls
                    atomicBatch(
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator))
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)
                            .signedByPayerAnd(batchOperator)
                            .payingWith(payer));
        }

        @LeakyHapiTest(overrides = {"consensus.handle.maxFollowingRecords"})
        @DisplayName("Exceeds child transactions limit should fail")
        //  BATCH_47
        public Stream<DynamicTest> exceedsChildTxnLimit() {
            final var batchOperator = "batchOperator";
            return hapiTest(
                    overriding("consensus.handle.maxFollowingRecords", "3"),
                    cryptoCreate(batchOperator),
                    atomicBatch(
                                    cryptoCreate("foo").batchKey(batchOperator),
                                    cryptoCreate("foo").batchKey(batchOperator),
                                    cryptoCreate("foo").batchKey(batchOperator),
                                    cryptoCreate("foo").batchKey(batchOperator))
                            .hasKnownStatus(MAX_CHILD_RECORDS_EXCEEDED)
                            .signedByPayerAnd(batchOperator));
        }

        @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
        @DisplayName("Exceeds gas limit should fail")
        //  BATCH_48
        public Stream<DynamicTest> exceedsGasLimit() {
            final var contract = "CalldataSize";
            final var function = "callme";
            final var payload = new byte[100];
            final var batchOperator = "batchOperator";
            return hapiTest(
                    overriding("contracts.maxGasPerSec", "2000000"),
                    cryptoCreate(batchOperator),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    atomicBatch(contractCall(contract, function, payload)
                                    .gas(2000001)
                                    .batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED));
        }

        @HapiTest
        @DisplayName("Bach contract call with 6kb payload, will fail")
        //  BATCH_50
        public Stream<DynamicTest> exceedsTxnSizeLimit() {
            final var contract = "CalldataSize";
            final var function = "callme";
            final var payload = new byte[MAX_CALL_DATA_SIZE];
            final var batchOperator = "batchOperator";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    atomicBatch(contractCall(contract, function, payload).batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator)
                            .hasPrecheck(TRANSACTION_OVERSIZE)
                            // the submitted transaction exceeds 6144 bytes and will have its
                            // gRPC request terminated immediately
                            .orUnavailableStatus());
        }

        @LeakyHapiTest(overrides = {"atomicBatch.maxNumberOfTransactions"})
        @DisplayName("Exceeds max number of inner transactions limit should fail")
        //  BATCH_52
        public Stream<DynamicTest> exceedsInnerTxnLimit() {
            final var batchOperator = "batchOperator";
            return hapiTest(
                    // set the maxInnerTxn to 2
                    overriding("atomicBatch.maxNumberOfTransactions", "2"),
                    cryptoCreate(batchOperator),
                    atomicBatch(
                                    cryptoCreate("foo").batchKey(batchOperator),
                                    cryptoCreate("foo").batchKey(batchOperator),
                                    cryptoCreate("foo").batchKey(batchOperator))
                            .hasKnownStatus(BATCH_SIZE_LIMIT_EXCEEDED)
                            .signedByPayerAnd(batchOperator));
        }

        @HapiTest
        @DisplayName("Resubmit batch after INSUFFICIENT_PAYER_BALANCE")
        @Disabled // Failed log validation: "Non-duplicate {} not cached for either payer or submitting node {}"
        // BATCH_53
        public Stream<DynamicTest> resubmitAfterInsufficientPayerBalance() {
            return hapiTest(
                    cryptoCreate("alice").balance(0L),
                    usableTxnIdNamed("failingBatch").payerId("alice"),
                    usableTxnIdNamed("innerTxn1"),
                    usableTxnIdNamed("innerTxn2"),
                    // batch will fail due to insufficient balance
                    atomicBatch(
                                    cryptoCreate("foo").txnId("innerTxn1").batchKey("alice"),
                                    cryptoCreate("foo").txnId("innerTxn1").batchKey("alice"))
                            .txnId("failingBatch")
                            .payingWith("alice")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                    // add some balance to alice
                    cryptoTransfer(movingHbar(FIVE_HBARS).between(GENESIS, "alice"))
                            .payingWith(GENESIS),
                    // resubmit the batch
                    atomicBatch(
                                    cryptoCreate("foo").txnId("innerTxn1").batchKey("alice"),
                                    cryptoCreate("foo").txnId("innerTxn1").batchKey("alice"))
                            .txnId("failingBatch")
                            .payingWith("alice"));
        }

        @HapiTest
        @DisplayName("Submit non batch inner transaction with batch key should fail")
        //  BATCH_54
        public Stream<DynamicTest> nonInnerTransactionHasBatchKeyFails() {
            final var batchPayer = "batchPayer";
            final var innerTnxPayer = "innerPayer";
            final var innerTxnId = "innerId";
            final var basicPayer = "basicPayer";
            final var innerTxn = cryptoCreate("foo1")
                    .balance(ONE_HBAR)
                    .txnId(innerTxnId)
                    .batchKey(batchPayer)
                    .payingWith(innerTnxPayer)
                    .via("innerTxn");

            return hapiTest(
                    cryptoCreate(batchPayer).balance(FIVE_HBARS),
                    cryptoCreate(innerTnxPayer).balance(FIVE_HBARS),
                    cryptoCreate(basicPayer).balance(FIVE_HBARS),
                    usableTxnIdNamed(innerTxnId).payerId(innerTnxPayer),
                    atomicBatch(innerTxn)
                            .batchKey(batchPayer)
                            .payingWith(batchPayer)
                            .via("batchTxn")
                            .hasKnownStatus(BATCH_KEY_SET_ON_NON_INNER_TRANSACTION),
                    newKeyNamed("newKey"),
                    cryptoCreate("foo2")
                            .balance(ONE_HBAR)
                            .batchKey("newKey")
                            .signedBy(DEFAULT_PAYER)
                            .payingWith(basicPayer)
                            .via("basicTxn")
                            .hasKnownStatus(BATCH_KEY_SET_ON_NON_INNER_TRANSACTION),
                    getAccountRecords(batchPayer).exposingTo(records -> assertEquals(1, records.size())),
                    getAccountRecords(basicPayer).exposingTo(records -> assertEquals(1, records.size())),
                    validateChargedUsd("batchTxn", 0.001),
                    validateChargedUsd("basicTxn", 0.05, 10));
        }

        @HapiTest
        @DisplayName("Submit non batch inner transaction with invalid batch key should fail")
        @Disabled // TODO: Enable this test when we have global batch key validation
        //  BATCH_55
        public Stream<DynamicTest> nonInnerTxnWithInvalidBatchKey() {
            return hapiTest(withOpContext((spec, opLog) -> {
                // create invalid key
                final var invalidKey = Key.newBuilder()
                        .setEd25519(ByteString.copyFrom(new byte[32]))
                        .build();
                // save invalid key in registry
                spec.registry().saveKey("invalidKey", invalidKey);
                // submit op with invalid batch key
                final var op = cryptoCreate("foo").batchKey("invalidKey").hasPrecheck(NOT_SUPPORTED);
                allRunFor(spec, op);
            }));
        }
    }

    @Nested
    @DisplayName("Fees - NEGATIVE")
    class FeesNegative {

        @HapiTest
        @DisplayName("Batch containing failing transfer still charges inner txn payer")
        // BATCH_64
        public Stream<DynamicTest> failingBatchStillChargesFees() {
            return hapiTest(
                    // create accounts and tokens
                    cryptoCreate("Alice").balance(ONE_HBAR),
                    cryptoCreate("Bob").balance(ONE_HBAR),
                    cryptoCreate("receiver"),
                    cryptoCreate("collector"),
                    cryptoCreate("treasury"),
                    tokenCreate("ftC").treasury("treasury"),
                    tokenCreate("ftB").treasury("treasury"),
                    tokenAssociate("collector", "ftB"),
                    tokenCreate("ftA")
                            .withCustom(fixedHtsFee(1, "ftB", "collector"))
                            .treasury("treasury"),
                    tokenAssociate("Bob", "ftA", "ftB", "ftC"),
                    tokenAssociate("receiver", "ftA", "ftB"),
                    cryptoTransfer(TokenMovement.moving(1, "ftA").between("treasury", "Bob")),
                    cryptoTransfer(TokenMovement.moving(1, "ftB").between("treasury", "Bob")),
                    cryptoTransfer(TokenMovement.moving(1, "ftC").between("treasury", "Bob")),
                    // batch txn
                    atomicBatch(
                                    cryptoTransfer(TokenMovement.moving(1, "ftA")
                                                    .between("Bob", "receiver"))
                                            .batchKey("Alice")
                                            .payingWith("Bob")
                                            .signedBy("Bob"),
                                    // will fail because receiver is not associated with ftC
                                    cryptoTransfer(TokenMovement.moving(1, "ftC")
                                                    .between("Bob", "receiver"))
                                            .batchKey("Alice")
                                            .payingWith("Bob")
                                            .signedBy("Bob"))
                            .payingWith("Alice")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)
                            .via("batchTxn"),
                    // asserts
                    getAccountRecords("Bob").exposingTo(records -> {
                        assertEquals(2, records.size());
                        // validate transactionFee matches the debit in the transferList
                        validateTransactionFees(records);
                    }),
                    getAccountRecords("Alice").exposingTo(records -> {
                        assertEquals(1, records.size());
                        // validate transactionFee matches the debit in the transferList
                        validateTransactionFees(records);
                    }),
                    getAccountBalance("collector").hasTokenBalance("ftB", 0),
                    getAccountBalance("receiver").hasTokenBalance("ftA", 0),
                    getAccountBalance("receiver").hasTokenBalance("ftC", 0));
        }

        @HapiTest
        @DisplayName("Batch containing expired transaction charges on rollback")
        // BATCH_66
        public Stream<DynamicTest> failingWithExpiryStillChargesFees() {
            return hapiTest(
                    // create accounts and tokens
                    cryptoCreate("Alice").balance(ONE_HBAR),
                    // batch txn
                    atomicBatch(
                                    tokenCreate("ftA").batchKey("Alice").payingWith("Alice"),
                                    tokenCreate("ftB")
                                            .withTxnTransform(txn -> TxnUtils.replaceTxnDuration(txn, -1L))
                                            .batchKey("Alice")
                                            .payingWith("Alice"))
                            .payingWith("Alice")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)
                            .via("batchTxn"),
                    // asserts
                    getAccountRecords("Alice").exposingTo(records -> assertEquals(2, records.size())));
        }

        @HapiTest
        @DisplayName("Expired batch does not charge fees")
        // BATCH_68
        public Stream<DynamicTest> failingBatchWithExpiryDoesNotChargeFees() {
            return hapiTest(
                    // create accounts and tokens
                    cryptoCreate("Alice").balance(ONE_HBAR),
                    cryptoCreate("Bob").balance(ONE_HBAR),
                    // batch txn
                    atomicBatch(
                                    tokenCreate("ftA").batchKey("Alice").payingWith("Bob"),
                                    tokenCreate("ftB").batchKey("Alice").payingWith("Bob"))
                            .payingWith("Alice")
                            .withTxnTransform(txn -> TxnUtils.replaceTxnDuration(txn, -1L))
                            .hasPrecheck(INVALID_TRANSACTION_DURATION)
                            .via("batchTxn"),
                    // asserts
                    getAccountBalance("Alice").hasTinyBars(ONE_HBAR),
                    getAccountBalance("Bob").hasTinyBars(ONE_HBAR));
        }
    }

    private void validateTransactionFees(final List<TransactionRecord> records) {
        for (var record : records) {
            final var debit = record.getTransferList().getAccountAmountsList().stream()
                    .filter(aa -> aa.getAmount() < 0)
                    .mapToInt(aa -> (int) -aa.getAmount())
                    .sum();
            assertEquals(debit, record.getTransactionFee());
        }
    }

    @Nested
    @DisplayName("Blacklisted inner transactions - NEGATIVE")
    class BlacklistedTransactions {

        @HapiTest
        @DisplayName("Batch containing nested batch")
        // BATCH_59
        public Stream<DynamicTest> batchContainingNestedBatch() {
            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    atomicBatch(atomicBatch(cryptoCreate("foo").batchKey("batchOperator"))
                                    .batchKey("batchOperator"))
                            .signedByPayerAnd("batchOperator")
                            .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST));
        }

        @HapiTest
        @DisplayName("Batch containing freeze transaction")
        // BATCH_60
        public Stream<DynamicTest> batchContainingFreezeTransactions() {
            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    atomicBatch(freezeOnly()
                                    .payingWith(GENESIS)
                                    .startingAt(Instant.now().plusSeconds(10))
                                    .batchKey("batchOperator")
                                    .signedByPayerAnd("batchOperator"))
                            .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST));
        }

        @HapiTest
        @DisplayName("Batch containing blacklisted and non-blacklisted transactions")
        // BATCH_61
        public Stream<DynamicTest> nonBlacklistedAndBlacklistedTransactions() {
            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    atomicBatch(
                                    cryptoCreate("foo").batchKey("batchOperator"),
                                    freezeOnly()
                                            .payingWith(GENESIS)
                                            .startingAt(Instant.now().plusSeconds(10))
                                            .batchKey("batchOperator")
                                            .signedByPayerAnd("batchOperator"))
                            .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST));
        }
    }

    @Nested
    @DisplayName("Signatures - NEGATIVE")
    class SignaturesNegative {

        @HapiTest
        @DisplayName("Batch transaction fails due to missing threshold key signatures")
        // BATCH_70
        public Stream<DynamicTest> missingThresholdKeySignaturesFails() {
            final var alice = "alice";
            final var bob = "bob";
            final var dave = "dave";
            final var thresholdKey = "thresholdKey";

            final KeyShape threshKeyShape = KeyShape.threshOf(2, PREDEFINED_SHAPE, PREDEFINED_SHAPE);

            final var innerTxn1 = cryptoCreate("foo1")
                    .balance(ONE_HBAR)
                    .batchKey(thresholdKey)
                    .payingWith(alice);

            final var innerTxn2 = cryptoCreate("foo2")
                    .balance(ONE_HBAR)
                    .batchKey(thresholdKey)
                    .payingWith(alice);

            return hapiTest(
                    cryptoCreate(alice),
                    cryptoCreate(bob),
                    cryptoCreate(dave),
                    newKeyNamed(thresholdKey).shape(threshKeyShape.signedWith(sigs(bob, dave))),
                    atomicBatch(innerTxn1, innerTxn2)
                            .payingWith(bob) // Bob submits the transaction
                            .signedBy(bob) // Missing Dave’s key, you can't sign with the threshold key
                            .hasKnownStatus(INVALID_SIGNATURE));
        }

        @HapiTest
        @DisplayName("Batch transaction passes when different batch keys signatures are present")
        // BATCH_71
        public Stream<DynamicTest> differentNecessaryBatchKeysArePresent() {
            final var alice = "alice";
            final var bob = "bob";
            final var dave = "dave";
            final var thresholdKey = "thresholdKey";

            final KeyShape threshKeyShape = KeyShape.threshOf(2, PREDEFINED_SHAPE, PREDEFINED_SHAPE);
            final var innerTxn1 = cryptoCreate("foo1")
                    .balance(ONE_HBAR)
                    .batchKey(thresholdKey)
                    .payingWith(alice);

            final var innerTxn2 =
                    cryptoCreate("foo2").balance(ONE_HBAR).batchKey(bob).payingWith(alice);

            return hapiTest(
                    cryptoCreate(alice),
                    cryptoCreate(bob),
                    cryptoCreate(dave),
                    newKeyNamed(thresholdKey).shape(threshKeyShape.signedWith(sigs(bob, dave))),
                    atomicBatch(innerTxn1, innerTxn2)
                            .payingWith(bob) // Bob submits the transaction
                            .signedBy(bob, dave), // Bob signs with the threshold key
                    getAccountBalance("foo1").hasTinyBars(ONE_HBAR),
                    getAccountBalance("foo2").hasTinyBars(ONE_HBAR));
        }

        @HapiTest
        @DisplayName("Batch transaction fails when one inner transaction has a different BatchKey")
        // BATCH_72 && BATCH_73
        public Stream<DynamicTest> batchWithDifferentBatchKeysFails() {
            final var alice = "alice";
            final var bob = "bob";
            final var batchKey1 = "batchKey1";
            final var batchKey2 = "batchKey2";

            return hapiTest(
                    cryptoCreate(alice),
                    cryptoCreate(bob),
                    newKeyNamed(batchKey1),
                    newKeyNamed(batchKey2),
                    atomicBatch(
                                    cryptoCreate("foo1").batchKey(batchKey1),
                                    cryptoCreate("foo2").batchKey(batchKey2))
                            .payingWith(alice) // Alice pays for the batch
                            .signedBy(batchKey1) // Alice signs with only batchKey1
                            .hasPrecheck(INVALID_SIGNATURE));
        }

        @HapiTest
        @DisplayName("Batch transaction fails when one inner transaction has no BatchKey set")
        // BATCH_74
        public Stream<DynamicTest> batchWithMissingBatchKeyFails() {
            final var alice = "alice";

            return hapiTest(
                    cryptoCreate(alice),
                    atomicBatch(cryptoCreate("foo1").batchKey(alice), cryptoCreate("foo2")) // No BatchKey set
                            .payingWith(alice) // Alice pays for the batch
                            .signedBy(alice) // Alice signs with the valid BatchKey
                            .hasPrecheck(MISSING_BATCH_KEY));
        }
    }
}
