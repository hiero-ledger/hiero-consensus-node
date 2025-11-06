// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountDetailsAsserts.accountDetailsWith;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.ED25519;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountDetails;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.explicit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemContractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.systemContractUndelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForGasOnlyForInnerTxn;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.RELAYER;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.contract.Utils.asHexedSolidityAddress;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoAddLiveHash;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoDeleteLiveHash;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_KEY_SET_ON_NON_INNER_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_CONTAINS_DUPLICATES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_CHILD_RECORDS_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_GAS_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_BATCH_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hederahashgraph.api.proto.java.CryptoAddLiveHashTransactionBody;
import com.hederahashgraph.api.proto.java.CryptoDeleteLiveHashTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenType;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

public class AtomicBatchNegativeTest {

    @Nested
    @DisplayName("Order and Execution - NEGATIVE")
    class OrderAndExecutionNegative {

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
                                    cryptoDelete(sender)
                                            .signedByPayerAnd(newKey)
                                            .batchKey(sender),
                                    cryptoTransfer(tinyBarsFromTo(GENESIS, sender, 1))
                                            .batchKey(sender)
                                            .hasKnownStatus(ACCOUNT_DELETED))
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
        @DisplayName("Batch fails with inner txn missing DEFAULT_PAYER key signature")
        public Stream<DynamicTest> missingInnerTxnPayerSignatureFails() {
            final var batchOperator = "batchOperator";
            final var innerTxnPayer = "innerPayer";
            // crete inner txn with innerTxnPayer, but sign only with DEFAULT_PAYER
            final var innerTxn = cryptoCreate("foo")
                    .balance(ONE_HBAR)
                    .hasKnownStatus(INVALID_PAYER_SIGNATURE)
                    .batchKey(batchOperator)
                    .payingWith(innerTxnPayer)
                    .signedBy(DEFAULT_PAYER);

            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HBAR),
                    cryptoCreate(innerTxnPayer).balance(ONE_HBAR),
                    // Since the inner txn is signed by DEFAULT_PAYER, it should fail
                    atomicBatch(innerTxn).payingWith(batchOperator).hasPrecheck(INVALID_SIGNATURE));
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
                    .payingWith(innerTxnPayer)
                    .hasKnownStatus(INVALID_TRANSACTION_START);
            final var validStart = Timestamp.newBuilder()
                    .setSeconds(Instant.now().getEpochSecond() + 1000)
                    .setNanos(1)
                    .build();

            return hapiTest(
                    cryptoCreate(innerTxnPayer).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(innerTxnId1).payerId(innerTxnPayer).validStart(validStart),
                    cryptoCreate(batchOperator).balance(ONE_HBAR),
                    atomicBatch(innerTxn1).payingWith(batchOperator).hasPrecheck(INVALID_TRANSACTION_START));
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
                    atomicBatch(innerTxn1, innerTxn2).payingWith(batchOperator).hasPrecheck(DUPLICATE_TRANSACTION));
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
                                    .batchKey("batchOperator")
                                    .hasKnownStatus(INVALID_TRANSACTION_DURATION))
                            .payingWith("batchOperator")
                            .hasPrecheck(INVALID_TRANSACTION_DURATION));
        }

        @HapiTest
        @DisplayName("Submit same batch twice should fail")
        // BATCH_42 BATCH_43
        public Stream<DynamicTest> submitSameBatch() {

            return hapiTest(
                    cryptoCreate("batchOperator").balance(FIVE_HBARS),
                    usableTxnIdNamed("successfulBatch").payerId("batchOperator"),
                    usableTxnIdNamed("failingBatch").payerId("batchOperator"),
                    cryptoCreate("sender").balance(ONE_HBAR),
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
                            .payingWith("batchOperator"),
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
                    contractCreate(contract).gas(1_000_000),
                    atomicBatch(contractCall(contract, function, payload)
                                    .gas(2000001)
                                    .batchKey(batchOperator)
                                    .hasKnownStatus(MAX_GAS_LIMIT_EXCEEDED))
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
                                    cryptoCreate("foo").txnId("innerTxn2").batchKey("alice"))
                            .txnId("failingBatch")
                            .payingWith("alice")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                    // add some balance to alice
                    cryptoTransfer(movingHbar(FIVE_HBARS).between(GENESIS, "alice"))
                            .payingWith(GENESIS),
                    // resubmit the batch
                    atomicBatch(
                                    cryptoCreate("foo").txnId("innerTxn1").batchKey("alice"),
                                    cryptoCreate("foo").txnId("innerTxn2").batchKey("alice"))
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
                    getAccountRecords(batchPayer)
                            .exposingNonStakingRecordsTo(records -> assertEquals(1, records.size())),
                    getAccountRecords(basicPayer)
                            .exposingNonStakingRecordsTo(records -> assertEquals(1, records.size())),
                    validateChargedUsd("batchTxn", 0.001),
                    validateChargedUsd("basicTxn", 0.05, 10));
        }

        @HapiTest
        @DisplayName("Submit non batch inner transaction with invalid batch key should fail")
        //  BATCH_55
        public Stream<DynamicTest> nonInnerTxnWithInvalidBatchKey() {
            return hapiTest(withOpContext((spec, opLog) -> {
                // create invalid key
                final var invalidKey = Key.newBuilder()
                        .setEd25519(ByteString.copyFrom(new byte[32]))
                        .build();
                // save invalid key in registry
                spec.registry().saveKey("invalidKey", invalidKey);
                // submit op with an invalid batch key
                final var cryptoCreateOp = cryptoCreate("foo")
                        .batchKey("invalidKey")
                        .hasKnownStatus(BATCH_KEY_SET_ON_NON_INNER_TRANSACTION);
                allRunFor(spec, cryptoCreateOp);
            }));
        }
    }

    @Nested
    @DisplayName("Throttles - NEGATIVE")
    class ThrottlesNegative {
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
                                            .batchKey(batchOperator))
                            // Should throttle at ingest
                            .hasPrecheck(BUSY)
                            .signedByPayerAnd(batchOperator)
                            .payingWith(payer));
        }

        @LeakyHapiTest(requirement = {THROTTLE_OVERRIDES})
        @DisplayName("Verify inner transaction front end throttle leaks capacity")
        @Tag(MATS)
        public Stream<DynamicTest> frontEndThrottleLeaksCapacity() {
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
                    // The artificial limits result in 1 contract call per second
                    overridingThrottles("testSystemFiles/artificial-limits.json"),
                    // Should throttle at ingest
                    atomicBatch(
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator))
                            .payingWith(batchOperator)
                            .hasPrecheck(BUSY),
                    // Wait for the throttle to be released
                    sleepForSeconds(1),
                    // Should throttle at ingest but this time defer status resolution
                    atomicBatch(
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .batchKey(batchOperator))
                            .deferStatusResolution()
                            .payingWith(batchOperator)
                            .hasPrecheck(BUSY),
                    // This should succeed, as the batch above should refund capacity
                    atomicBatch(contractCall(contract, function, payload)
                                    .payingWith(payer)
                                    .batchKey(batchOperator))
                            .payingWith(batchOperator));
        }

        @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
        @DisplayName("Verify inner transaction gets gas throttled and refunds gas capacity")
        public Stream<DynamicTest> innerBatchGetsGasThrottledAndLeaksCapacity() {
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
                    overriding("contracts.maxGasPerSec", "500000"),
                    // Should throttle as total gas is more than maxGasPerSec
                    atomicBatch(
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .gas(300_000)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .gas(300_000)
                                            .batchKey(batchOperator))
                            .payingWith(batchOperator)
                            .hasPrecheck(BUSY),
                    // Wait for the throttle capacity to leak
                    sleepForSeconds(1),
                    // Should throttle as total gas is more than maxGasPerSec, but this time defer status resolution
                    atomicBatch(
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .gas(300_000)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .gas(300_000)
                                            .batchKey(batchOperator))
                            .deferStatusResolution()
                            .payingWith(batchOperator)
                            .hasPrecheck(BUSY),
                    // This should succeed, as the batch above should refund capacity
                    atomicBatch(contractCall(contract, function, payload)
                                    .payingWith(payer)
                                    .gas(500_000)
                                    .batchKey(batchOperator))
                            .payingWith(batchOperator));
        }

        @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
        @DisplayName("Verify privileged accounts are throttle exempt for inner transactions")
        public Stream<DynamicTest> privilegedAccountsAreThrottleExempt() {
            final var batchOperator = "batchOperator";
            final var contract = "CalldataSize";
            final var function = "callme";
            final var payload = new byte[100];
            final var payer = "payer";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    cryptoCreate(payer),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    overriding("contracts.maxGasPerSec", "500000"),
                    // Should pass as privileged accounts are throttle exempt
                    atomicBatch(
                                    contractCall(contract, function, payload)
                                            .payingWith(DEFAULT_PAYER)
                                            .gas(300_000)
                                            .batchKey(batchOperator),
                                    contractCall(contract, function, payload)
                                            .payingWith(DEFAULT_PAYER)
                                            .gas(300_000)
                                            .batchKey(batchOperator))
                            .payingWith(batchOperator));
        }

        @LeakyHapiTest(overrides = {"contracts.maxGasPerSec"})
        @DisplayName("Inner transactions are not throttle exempt when the batch operator is privileged")
        public Stream<DynamicTest> notThrottleExemptIfTheBatchOperatorIsPrivileged() {
            final var contract = "CalldataSize";
            final var function = "callme";
            final var payload = new byte[100];
            final var payer = "payer";
            return hapiTest(
                    cryptoCreate(payer),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    overriding("contracts.maxGasPerSec", "500000"),
                    // Should be throttled as the inner transactions are not signed by privileged accounts
                    atomicBatch(
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .gas(300_000)
                                            .batchKey(DEFAULT_PAYER),
                                    contractCall(contract, function, payload)
                                            .payingWith(payer)
                                            .gas(300_000)
                                            .batchKey(DEFAULT_PAYER))
                            .payingWith(DEFAULT_PAYER)
                            .hasPrecheck(BUSY));
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
                    cryptoTransfer(moving(1, "ftA").between("treasury", "Bob")),
                    cryptoTransfer(moving(1, "ftB").between("treasury", "Bob")),
                    cryptoTransfer(moving(1, "ftC").between("treasury", "Bob")),
                    // batch txn
                    atomicBatch(
                                    cryptoTransfer(moving(1, "ftA").between("Bob", "receiver"))
                                            .batchKey("Alice")
                                            .fee(ONE_HBAR)
                                            .payingWith("Bob")
                                            .signedBy("Bob")
                                            .hasKnownStatus(REVERTED_SUCCESS),
                                    // will fail because receiver is not associated with ftC
                                    cryptoTransfer(moving(1, "ftC").between("Bob", "receiver"))
                                            .batchKey("Alice")
                                            .payingWith("Bob")
                                            .signedBy("Bob")
                                            .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                            .payingWith("Alice")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED)
                            .via("batchTxn"),
                    // asserts
                    getAccountRecords("Bob").exposingNonStakingRecordsTo(records -> {
                        assertEquals(2, records.size());
                        // validate transactionFee matches the debit in the transferList
                        validateTransactionFees(records);
                    }),
                    getAccountRecords("Alice").exposingNonStakingRecordsTo(records -> {
                        assertEquals(1, records.size());
                        // validate transactionFee matches the debit in the transferList
                        validateTransactionFees(records);
                    }),
                    getAccountBalance("collector").hasTokenBalance("ftB", 0),
                    getAccountBalance("receiver").hasTokenBalance("ftA", 0),
                    getAccountBalance("receiver").hasTokenBalance("ftC", 0));
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

        @HapiTest
        @DisplayName("schedule transactions are blacklisted in atomic batch")
        public Stream<DynamicTest> scheduleBlackList() {
            return hapiTest(
                    cryptoCreate("batchOperator"),
                    atomicBatch(scheduleCreate("schedule", cryptoCreate("foo")).batchKey("batchOperator"))
                            .payingWith("batchOperator")
                            .hasKnownStatus(BATCH_TRANSACTION_IN_BLACKLIST),
                    scheduleCreate("schedule", cryptoCreate("foo")),
                    atomicBatch(scheduleSign("schedule").batchKey("batchOperator"))
                            .payingWith("batchOperator")
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

    @Nested
    @DisplayName("Nonce Tests")
    class NonceTests {
        private static final String INTERNAL_CALLEE_CONTRACT = "InternalCallee";
        private static final String BATCH_OPERATOR = "batchOperator";
        private static final String EXTERNAL_FUNCTION = "externalFunction";
        private static final String REVERT_FUNCTION = "revertWithRevertReason";

        @HapiTest
        @DisplayName("Nonce gets updated after contract reversion inside batch")
        final Stream<DynamicTest> nonceUpdatedAfterEvmReversionDueContractLogic() {
            final var gasLimit = 215_000L;
            return hapiTest(
                    cryptoCreate(BATCH_OPERATOR),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                    uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                    contractCreate(INTERNAL_CALLEE_CONTRACT),
                    atomicBatch(ethereumCall(INTERNAL_CALLEE_CONTRACT, REVERT_FUNCTION)
                                    .type(EthTxData.EthTransactionType.EIP1559)
                                    .signingWith(SECP_256K1_SOURCE_KEY)
                                    .payingWith(RELAYER)
                                    .nonce(0)
                                    .gasLimit(gasLimit)
                                    .hasKnownStatus(CONTRACT_REVERT_EXECUTED)
                                    .via("ethCall")
                                    .batchKey("batchOperator"))
                            .payingWith("batchOperator")
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    validateChargedUsdForGasOnlyForInnerTxn("ethCall", "batchTxn", 0.00183, 5),
                    getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                            .has(accountWith().nonce(1L)),
                    getTxnRecord("ethCall")
                            .hasPriority(recordWith()
                                    .status(CONTRACT_REVERT_EXECUTED)
                                    .contractCallResult(resultWith().signerNonce(1L))));
        }

        @HapiTest
        @DisplayName("Nonce gets updated after successful contract call inside batch")
        @Tag(MATS)
        final Stream<DynamicTest> nonceUpdatedAfterSuccessfulInternalCall() {
            final var internalCalleeContract = "InternalCallee";
            final var externalFunction = "externalFunction";
            final var gasLimit = 215_000L;
            return hapiTest(
                    cryptoCreate("batchOperator"),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                    uploadInitCode(internalCalleeContract),
                    contractCreate(internalCalleeContract),
                    atomicBatch(ethereumCall(internalCalleeContract, externalFunction)
                                    .type(EthTxData.EthTransactionType.EIP1559)
                                    .signingWith(SECP_256K1_SOURCE_KEY)
                                    .payingWith(RELAYER)
                                    .nonce(0)
                                    .gasLimit(gasLimit)
                                    .via("ethCall")
                                    .batchKey("batchOperator"))
                            .payingWith("batchOperator"),
                    getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                            .has(accountWith().nonce(1L)),
                    getTxnRecord("ethCall")
                            .hasPriority(
                                    recordWith().contractCallResult(resultWith().signerNonce(1L))));
        }

        @HapiTest
        @DisplayName("Nonce not updated when intrinsic gas check fails")
        final Stream<DynamicTest> nonceNotUpdatedWhenIntrinsicGasHandlerCheckFailed() {
            return hapiTest(
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoCreate(BATCH_OPERATOR),
                    cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HBAR)),
                    uploadInitCode(INTERNAL_CALLEE_CONTRACT),
                    contractCreate(INTERNAL_CALLEE_CONTRACT),
                    atomicBatch(ethereumCall(INTERNAL_CALLEE_CONTRACT, EXTERNAL_FUNCTION)
                                    .type(EthTxData.EthTransactionType.EIP1559)
                                    .signingWith(SECP_256K1_SOURCE_KEY)
                                    .payingWith(RELAYER)
                                    .nonce(0)
                                    .gasLimit(21_000L)
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .hasPrecheck(INSUFFICIENT_GAS),
                    getAliasedAccountInfo(SECP_256K1_SOURCE_KEY)
                            .has(accountWith().nonce(0L)));
        }
    }

    @HapiTest
    @DisplayName("Non default inner transaction node ID should fail")
    public Stream<DynamicTest> nonDefaultInnerTxnIdFails() {
        var batchOperator = "batchOperator";
        var innerCryptoTxn = cryptoCreate("foo").setNode("1").batchKey(batchOperator);

        return hapiTest(
                cryptoCreate(batchOperator),
                atomicBatch(innerCryptoTxn).payingWith(batchOperator).hasPrecheck(INVALID_NODE_ACCOUNT_ID));
    }

    /**
     * Rollback contract emitted logs on fail.
     * @return hapi test
     */
    @HapiTest
    @DisplayName("Rollback contract emitted logs on fail")
    public Stream<DynamicTest> rollbackLogs() {
        final var token = "token";
        final AtomicReference<Address> tokenAddress = new AtomicReference<>();
        final var treasury = "treasury";
        final var mintContract = "MintContract";
        final var tokenSupplyKey = "tokenSupplyKey";
        final var batchOperator = "batchOperator";
        final var receiver = "receiver";
        return hapiTest(
                cryptoCreate(treasury),
                tokenCreate(token)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(100)
                        .treasury(treasury)
                        .adminKey(treasury)
                        .supplyKey(treasury)
                        .exposingAddressTo(tokenAddress::set),
                cryptoCreate(batchOperator),
                cryptoCreate(receiver),
                uploadInitCode(mintContract),
                sourcing(() -> contractCreate(mintContract, tokenAddress.get())),
                // token supply key
                newKeyNamed(tokenSupplyKey)
                        .shape(KeyShape.threshOf(1, ED25519, CONTRACT).signedWith(sigs(ON, mintContract))),
                tokenUpdate(token).supplyKey(tokenSupplyKey).signedByPayerAnd(treasury),
                // failing batch
                atomicBatch(
                                // call with logs
                                contractCall(mintContract, "mintFungibleTokenWithEvent", BigInteger.valueOf(1))
                                        .batchKey(batchOperator)
                                        .via("call"),
                                // failing txn
                                cryptoTransfer(moving(1, token).between(treasury, "receiver"))
                                        .batchKey(batchOperator)
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .payingWith(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED),
                getTxnRecord("call").logged());
    }

    /**
     * Rollback successful contract delete, will clear all state changes in the block stream.
     * Stream validation should pass. Contract ID should be derived from the transaction body.
     *
     * @return hapi test
     */
    @HapiTest
    @DisplayName("Rollback contract delete with evm address")
    public Stream<DynamicTest> rollbackContractDeleteWithEvmAddress() {
        final var contract = "Logs";
        final AtomicReference<String> evmAddress = new AtomicReference<>();
        final var batchOperator = "batchOperator";
        final var token = "token";
        final var receiver = "receiver";
        final var treasury = "treasury";
        return hapiTest(
                cryptoCreate(batchOperator),
                cryptoCreate(receiver),
                cryptoCreate(treasury),
                tokenCreate(token).initialSupply(1).treasury(treasury),
                uploadInitCode(contract),
                contractCreate(contract)
                        .payingWith(GENESIS)
                        .adminKey(treasury)
                        .exposingContractIdTo(id -> evmAddress.set(asHexedSolidityAddress(id))),
                sourcing(() -> atomicBatch(
                                contractDelete(evmAddress.get())
                                        .payingWith(GENESIS)
                                        .signedBy(GENESIS, treasury)
                                        .batchKey(batchOperator),
                                cryptoTransfer(moving(1, token).between(treasury, receiver))
                                        .batchKey(batchOperator)
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .payingWith(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED)));
    }

    /**
     * Rollback successful contract delete, will clear all state changes in the block stream.
     * Stream validation should pass. Contract ID should be derived from the transaction body.
     *
     * @return hapi test
     */
    @HapiTest
    @DisplayName("Rollback contract delete")
    public Stream<DynamicTest> rollbackContractDelete() {
        final var contract = "Logs";
        final var batchOperator = "batchOperator";
        final var token = "token";
        final var receiver = "receiver";
        final var treasury = "treasury";
        return hapiTest(
                cryptoCreate(batchOperator),
                cryptoCreate(receiver),
                cryptoCreate(treasury),
                tokenCreate(token).initialSupply(1).treasury(treasury),
                uploadInitCode(contract),
                contractCreate(contract).adminKey(treasury),
                atomicBatch(
                                contractDelete(contract).payingWith(treasury).batchKey(batchOperator),
                                cryptoTransfer(moving(1, token).between(treasury, receiver))
                                        .batchKey(batchOperator)
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                        .payingWith(batchOperator)
                        .hasKnownStatus(INNER_TRANSACTION_FAILED));
    }

    @HapiTest
    public Stream<DynamicTest> systemDeleteWillFail() {
        final var batchOperator = "batchOperator";
        final var payer = "payer";
        final var contract = "Logs";
        return hapiTest(
                cryptoCreate(batchOperator),
                cryptoCreate(payer),
                uploadInitCode(contract),
                contractCreate(contract).adminKey(payer),
                atomicBatch(systemContractDelete(contract).batchKey(batchOperator))
                        .payingWith(batchOperator)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    public Stream<DynamicTest> systemUndeleteWillFail() {
        final var batchOperator = "batchOperator";
        final var payer = "payer";
        final var contract = "Logs";
        return hapiTest(
                cryptoCreate(batchOperator),
                cryptoCreate(payer),
                uploadInitCode(contract),
                contractCreate(contract).adminKey(payer),
                atomicBatch(systemContractUndelete(contract).batchKey(batchOperator))
                        .payingWith(batchOperator)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    public Stream<DynamicTest> addLiveHashIsUnsupported() {
        final var batchOperator = "batchOperator";
        return hapiTest(
                cryptoCreate(batchOperator),
                atomicBatch(explicit(
                                        CryptoAddLiveHash,
                                        (spec, b) -> b.setCryptoAddLiveHash(
                                                CryptoAddLiveHashTransactionBody.getDefaultInstance()))
                                .batchKey(batchOperator))
                        .payingWith(batchOperator)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    public Stream<DynamicTest> deleteLiveHashIsUnsupported() {
        final var batchOperator = "batchOperator";
        return hapiTest(
                cryptoCreate(batchOperator),
                atomicBatch(explicit(
                                        CryptoDeleteLiveHash,
                                        (spec, b) -> b.setCryptoDeleteLiveHash(
                                                CryptoDeleteLiveHashTransactionBody.getDefaultInstance()))
                                .batchKey(batchOperator))
                        .payingWith(batchOperator)
                        .hasPrecheck(NOT_SUPPORTED));
    }

    @HapiTest
    @DisplayName(value = "Duplicated txn inside batch")
    public Stream<DynamicTest> duplicatedTxnInBatch() {
        final var batchOperator = "batchOperator";
        final var innerTxnId = "innerId";

        return hapiTest(
                usableTxnIdNamed(innerTxnId),
                cryptoCreate(batchOperator).balance(ONE_HBAR),
                // submit first txn
                atomicBatch(cryptoCreate("account")
                                .balance(ONE_HBAR)
                                .txnId(innerTxnId)
                                .batchKey(batchOperator))
                        .payingWith(batchOperator),
                // duplicate inside batch
                atomicBatch(cryptoCreate("account")
                                .balance(ONE_HBAR)
                                .txnId(innerTxnId)
                                .batchKey(batchOperator))
                        .payingWith(batchOperator)
                        .hasPrecheck(DUPLICATE_TRANSACTION));
    }
}
