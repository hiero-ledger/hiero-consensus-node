/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_CONTAINS_DUPLICATES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecSetup.TxnProtoStructure;
import com.hederahashgraph.api.proto.java.Key;
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
                                    scheduleSign("schedule")
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .payingWith(sender)
                                            .batchKey(batchOperator),
                                    // failing transfer
                                    cryptoTransfer(tinyBarsFromTo(sender, receiver, ONE_HUNDRED_HBARS))
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
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
                                    cryptoUpdate(sender)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .key(newKey)
                                            .batchKey(sender),
                                    cryptoDelete(sender)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .batchKey(sender),
                                    cryptoTransfer(tinyBarsFromTo(GENESIS, sender, 1))
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
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
    }

    @Nested
    @DisplayName("Batch Constraints - NEGATIVE")
    class BatchConstraintsNegative {

        @HapiTest
        @DisplayName("Empty batch should fail")
        @Disabled // TODO: enable this when we have pure checks
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
                    atomicBatch(cryptoCreate("foo")
                                    .batchKey("batchOperator")
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED))
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
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED)
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
                    atomicBatch(cryptoCreate("foo")
                                    .batchKey("batchOperator")
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED))
                            .txnId("successfulBatch")
                            .payingWith("batchOperator"),
                    atomicBatch(cryptoCreate("foo")
                                    .batchKey("batchOperator")
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED))
                            .txnId("successfulBatch")
                            .payingWith("batchOperator")
                            .hasPrecheck(DUPLICATE_TRANSACTION),

                    // failing batch duplication
                    atomicBatch(cryptoTransfer(movingHbar(10L).between("sender", "receiver"))
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                    .batchKey("batchOperator")
                                    .signedByPayerAnd("sender"))
                            .txnId("failingBatch")
                            .payingWith("batchOperator")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    atomicBatch(cryptoTransfer(movingHbar(10L).between("sender", "receiver"))
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                    .batchKey("batchOperator")
                                    .signedByPayerAnd("sender"))
                            .txnId("failingBatch")
                            .payingWith("batchOperator")
                            .hasPrecheck(DUPLICATE_TRANSACTION));
        }

        @HapiTest
        @DisplayName("Submit batch with duplicated inner txn should fail")
        @Disabled // TODO: enable this when we have pure checks
        // BATCH_44
        public Stream<DynamicTest> duplicatedInnerTxn() {
            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    usableTxnIdNamed("innerId").payerId("batchOperator"),
                    withOpContext((spec, opLog) -> {
                        var txn = cryptoCreate("foo")
                                .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                .txnId("innerId")
                                .batchKey("batchOperator")
                                .payingWith("batchOperator")
                                .signedTxnFor(spec);
                        var batchOp = atomicBatch()
                                // add same inner transaction twice
                                .addTransaction(txn)
                                .addTransaction(txn)
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
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED)
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
                                    cryptoCreate("foo")
                                            .batchKey(batchOperator)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED),
                                    cryptoCreate("foo")
                                            .batchKey(batchOperator)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED),
                                    cryptoCreate("foo")
                                            .batchKey(batchOperator)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED),
                                    cryptoCreate("foo")
                                            .batchKey(batchOperator)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED))
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
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED)
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
                    atomicBatch(contractCall(contract, function, payload)
                                    .batchKey(batchOperator)
                                    .withProtoStructure(TxnProtoStructure.NORMALIZED))
                            .signedByPayerAnd(batchOperator)
                            .hasPrecheck(TRANSACTION_OVERSIZE)
                            // the submitted transaction exceeds 6144 bytes and will have its
                            // gRPC request terminated immediately
                            .orUnavailableStatus());
        }

        @LeakyHapiTest(overrides = {"atomicBatch.maxNumberOfTransactions"})
        @DisplayName("Exceeds max number of inner transactions limit should fail")
        @Disabled // TODO: enable this test when we have the maxInnerTxn property
        //  BATCH_52
        public Stream<DynamicTest> exceedsInnerTxnLimit() {
            final var batchOperator = "batchOperator";
            return hapiTest(
                    // set the maxInnerTxn to 2
                    overriding("atomicBatch.maxNumberOfTransactions", "2"),
                    cryptoCreate(batchOperator),
                    atomicBatch(
                                    cryptoCreate("foo")
                                            .batchKey(batchOperator)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED),
                                    cryptoCreate("foo")
                                            .batchKey(batchOperator)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED),
                                    cryptoCreate("foo")
                                            .batchKey(batchOperator)
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED))
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
                                    cryptoCreate("foo")
                                            .txnId("innerTxn1")
                                            .batchKey("alice")
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED),
                                    cryptoCreate("foo")
                                            .txnId("innerTxn1")
                                            .batchKey("alice")
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED))
                            .txnId("failingBatch")
                            .payingWith("alice")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                    // add some balance to alice
                    cryptoTransfer(movingHbar(FIVE_HBARS).between(GENESIS, "alice"))
                            .payingWith(GENESIS),
                    // resubmit the batch
                    atomicBatch(
                                    cryptoCreate("foo")
                                            .txnId("innerTxn1")
                                            .batchKey("alice")
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED),
                                    cryptoCreate("foo")
                                            .txnId("innerTxn1")
                                            .batchKey("alice")
                                            .withProtoStructure(TxnProtoStructure.NORMALIZED))
                            .txnId("failingBatch")
                            .payingWith("alice"));
        }

        @HapiTest
        @DisplayName("Submit non batch inner transaction with batch key should fail")
        @Disabled // TODO: Enable this test when we have global batch key validation
        //  BATCH_54
        public Stream<DynamicTest> nonInnerTxnWithBatchKey() {
            final var batchOperator = "batchOperator";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    cryptoCreate("foo").batchKey(batchOperator).hasPrecheck(NOT_SUPPORTED));
        }

        @HapiTest
        @DisplayName("Submit non batch inner transaction with invalid batch key should fail")
        @Disabled // TODO: Enable this test when we have global batch key validation
        //  BATCH_54
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
}
