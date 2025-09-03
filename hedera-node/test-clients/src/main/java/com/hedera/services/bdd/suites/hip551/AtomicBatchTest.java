// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip551;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.spec.HapiSpec.customizedHapiTest;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts.resultWith;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.CONTRACT;
import static com.hedera.services.bdd.spec.keys.KeyShape.PREDEFINED_SHAPE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.keys.SigMapGenerator.Nature.FULL_PREFIXES;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.uniqueWithFullPrefixesFor;
import static com.hedera.services.bdd.spec.keys.TrieSigMapGenerator.withNature;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAliasedAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromToWithAlias;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedConsensusHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.accountAmount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.childRecordsCheck;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.transferList;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.verify;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.FIVE_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.MAX_CALL_DATA_SIZE;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SOURCE_KEY;
import static com.hedera.services.bdd.suites.HapiSuite.THROTTLE_DEFS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.contract.precompile.ContractBurnHTSSuite.ALICE;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.createHollowAccountFrom;
import static com.hedera.services.bdd.suites.crypto.AutoCreateUtils.updateSpecFor;
import static com.hedera.services.bdd.suites.utils.MiscEETUtils.genRandomBytes;
import static com.hedera.services.bdd.suites.utils.sysfiles.serdes.ThrottleDefsLoader.protoDefsFromResource;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.PAYER_ACCOUNT_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.esaulpaugh.headlong.abi.Address;
import com.esaulpaugh.headlong.abi.Tuple;
import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.dsl.annotations.Contract;
import com.hedera.services.bdd.spec.dsl.entities.SpecContract;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.OverlappingKeyGenerator;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.utilops.RunnableOp;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

public class AtomicBatchTest {
    @HapiTest
    public Stream<DynamicTest> validateFeesForChildren() {
        final double BASE_FEE_BATCH_TRANSACTION = 0.001;
        final double BASE_FEE_HBAR_CRYPTO_TRANSFER = 0.0001;
        final double BASE_FEE_SUBMIT_MESSAGE_CUSTOM_FEE = 0.05;

        final var innerTxn1 = cryptoTransfer(tinyBarsFromTo("alice", "bob", ONE_HBAR))
                .payingWith("alice")
                .via("innerTxn")
                .blankMemo()
                .batchKey("batchOperator");
        final var innerTxn2 = submitMessageTo("topic")
                .message("TEST")
                .payingWith("bob")
                .via("innerTxn2")
                .blankMemo()
                .batchKey("batchOperator");
        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("submitKey"),
                newKeyNamed("feeScheduleKey"),
                cryptoCreate("batchOperator").balance(ONE_HBAR),
                cryptoCreate("alice").balance(2 * ONE_HBAR),
                cryptoCreate("bob").balance(4 * ONE_HBAR),
                cryptoCreate("collector").balance(0L),
                createTopic("topic")
                        .adminKeyName("adminKey")
                        .feeScheduleKeyName("feeScheduleKey")
                        .withConsensusCustomFee(fixedConsensusHbarFee(ONE_HBAR, "collector")),
                atomicBatch(innerTxn1, innerTxn2).payingWith("batchOperator").via("batchTxn"),
                validateChargedUsd("batchTxn", BASE_FEE_BATCH_TRANSACTION),
                validateInnerTxnChargedUsd("innerTxn", "batchTxn", BASE_FEE_HBAR_CRYPTO_TRANSFER, 5),
                validateInnerTxnChargedUsd("innerTxn2", "batchTxn", BASE_FEE_SUBMIT_MESSAGE_CUSTOM_FEE, 5));
    }

    @HapiTest
    @DisplayName("Validate batch transaction passed")
    public Stream<DynamicTest> simpleBatchTest() {
        final var batchOperator = "batchOperator";
        final var innerTxnPayer = "innerPayer";

        // create inner txn with:
        // - custom txn id -> for getting the record
        // - batch key -> for batch operator to sign
        // - payer -> for paying the fee
        final var innerTxn = cryptoCreate("foo")
                .balance(ONE_HBAR)
                .via("innerTxn")
                .batchKey(batchOperator)
                .payingWith(innerTxnPayer);

        return hapiTest(
                // create batch operator
                cryptoCreate(batchOperator).balance(ONE_HBAR),
                // create another payer for the inner txn
                cryptoCreate(innerTxnPayer).balance(ONE_HUNDRED_HBARS),
                // create a batch txn
                atomicBatch(innerTxn).payingWith(batchOperator).via("batchTxn"),
                // get and log inner txn record
                getTxnRecord("innerTxn").logged(),
                // validate the batch txn result
                getAccountBalance("foo").hasTinyBars(ONE_HBAR),
                validateChargedUsd("batchTxn", 0.001));
    }

    @HapiTest
    @DisplayName("Validate multi batch transaction passed")
    public Stream<DynamicTest> multiBatchSuccess() {
        final var batchOperator = "batchOperator";
        final var innerTxnPayer = "innerPayer";
        final var account1 = "foo1";
        final var account2 = "foo2";
        final var atomicTxn = "atomicTxn";

        final var innerTxn1 = cryptoCreate(account1)
                .balance(ONE_HBAR)
                .via("innerTxn1")
                .batchKey(batchOperator)
                .payingWith(innerTxnPayer);
        final var innerTxn2 = cryptoCreate(account2)
                .balance(ONE_HBAR)
                .via("innerTxn2")
                .batchKey(batchOperator)
                .payingWith(innerTxnPayer);

        return hapiTest(
                cryptoCreate(batchOperator).balance(ONE_HBAR),
                cryptoCreate(innerTxnPayer).balance(ONE_HUNDRED_HBARS),
                atomicBatch(innerTxn1, innerTxn2).payingWith(batchOperator).via(atomicTxn),
                getTxnRecord(atomicTxn).logged(),
                getTxnRecord("innerTxn1").logged(),
                getTxnRecord("innerTxn2").logged(),
                getAccountBalance(account1).hasTinyBars(ONE_HBAR),
                getAccountBalance(account2).hasTinyBars(ONE_HBAR));
    }

    @HapiTest
    public Stream<DynamicTest> settingSameSlotValueInMultipleCallsPassesStreamValidation(
            @Contract(contract = "Multipurpose", creationGas = 500_000L) SpecContract contract) {
        return hapiTest(
                // Eagerly create the contract so we can reference its name below
                contract.getInfo(),
                cryptoCreate("batchOperator"),
                usableTxnIdNamed("aInner").payerId("batchOperator"),
                usableTxnIdNamed("bInner").payerId("batchOperator"),
                usableTxnIdNamed("cInner").payerId("batchOperator"),
                atomicBatch(
                                contractCall(contract.name(), "believeIn", 8L)
                                        .txnId("aInner")
                                        .batchKey("batchOperator")
                                        .payingWith("batchOperator"),
                                contractCall(contract.name(), "believeIn", 16L)
                                        .txnId("bInner")
                                        .batchKey("batchOperator")
                                        .payingWith("batchOperator"),
                                contractCall(contract.name(), "believeIn", 32L)
                                        .txnId("cInner")
                                        .batchKey("batchOperator")
                                        .payingWith("batchOperator"))
                        .payingWith("batchOperator"),
                contract.staticCall("pick").andAssert(op -> op.hasResult(32L))
                // And StreamValidationTest must not fail on the traces of the first two contract
                // calls just because the same slot they use is overwritten by the third call
                );
    }

    @HapiTest
    @DisplayName("Batch with multiple children passes")
    public Stream<DynamicTest> batchWithMultipleChildren() {
        final var batchOperator = "batchOperator";
        final var innerTnxPayer = "innerPayer";
        final var account2 = "foo2";
        final var atomicTxn = "atomicTxn";
        final var alias = "alias";
        final AtomicReference<Timestamp> parentConsTime = new AtomicReference<>();

        final var innerTxn1 = cryptoTransfer(movingHbar(10L).between(innerTnxPayer, alias))
                .via("innerTxn1")
                .batchKey(batchOperator)
                .payingWith(innerTnxPayer);
        final var innerTxn2 = cryptoCreate(account2)
                .balance(ONE_HBAR)
                .via("innerTxn2")
                .batchKey(batchOperator)
                .payingWith(innerTnxPayer);
        return hapiTest(
                // set up
                newKeyNamed(alias),
                cryptoCreate(batchOperator).balance(ONE_HBAR),
                cryptoCreate(innerTnxPayer).balance(ONE_HUNDRED_HBARS),
                // submit atomic batch with 3 inner txns
                atomicBatch(innerTxn1, innerTxn2).payingWith(batchOperator).via(atomicTxn),
                getTxnRecord(atomicTxn)
                        .exposingTo(record -> parentConsTime.set(record.getConsensusTimestamp()))
                        .logged(),
                // All atomic batch transactions should have the same parentConsTime set
                // the same as the batch user txn
                sourcing(() -> getTxnRecord("innerTxn1")
                        .hasParentConsensusTime(parentConsTime.get())
                        .andAllChildRecords()
                        .hasNonStakingChildRecordCount(1)
                        .hasChildRecords(recordWith().status(SUCCESS))
                        .logged()),
                sourcing(() -> getTxnRecord("innerTxn2")
                        .hasParentConsensusTime(parentConsTime.get())
                        .andAllChildRecords()
                        .logged()));
    }

    @Nested
    @DisplayName("Batch Constraints - POSITIVE")
    class BatchConstraintsPositive {

        @LeakyHapiTest
        @DisplayName("Batch with max number of inner transaction")
        // BATCH_01
        public Stream<DynamicTest> maxInnerTxn() {
            final var payer = "payer";
            final var transferTxn = "transferTxn";
            final var batchOperator = "batchOperator";

            return customizedHapiTest(
                    // set the maxInnerTxn to 2
                    Map.of("atomicBatch.maxNumberOfTransactions", "2"),
                    cryptoCreate(batchOperator),
                    cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                    newKeyNamed("bar"),
                    // create a batch with the maximum number of inner transactions
                    // even if we have 1 child transaction, the batch should succeed
                    atomicBatch(
                                    cryptoCreate("foo").balance(ONE_HBAR).batchKey(batchOperator),
                                    cryptoTransfer(tinyBarsFromToWithAlias(payer, "bar", 10))
                                            .batchKey(batchOperator)
                                            .via(transferTxn)
                                            .payingWith(payer))
                            .signedByPayerAnd(batchOperator),
                    getReceipt(transferTxn).andAnyChildReceipts().hasChildAutoAccountCreations(1));
        }

        @LeakyHapiTest(requirement = {THROTTLE_OVERRIDES})
        @DisplayName("Batch contract call with the TPS limit")
        //  BATCH_02
        public Stream<DynamicTest> contractCallTPSLimit() {
            final var batchOperator = "batchOperator";
            final var contract = "CalldataSize";
            final var function = "callme";
            final byte[] payload = new byte[100];
            final var payer = "payer";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    cryptoCreate(payer).balance(ONE_HBAR),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    overridingThrottles("testSystemFiles/artificial-limits.json"),
                    // create a batch with 1 contract calls (the TPS limit is 3),
                    // and after the frontend scale we can send only 1 per second
                    atomicBatch(contractCall(contract, function, payload)
                                    .payingWith(payer)
                                    .batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator)
                            .payingWith(payer));
        }

        @LeakyHapiTest
        @DisplayName("Batch contract call with the gas limit")
        //  BATCH_03
        public Stream<DynamicTest> contractCallGasLimit() {
            final var contract = "CalldataSize";
            final var function = "callme";
            final var payload = new byte[100];
            final var batchOperator = "batchOperator";
            return customizedHapiTest(
                    Map.of("contracts.maxGasPerSec", "2000000"),
                    cryptoCreate(batchOperator),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    atomicBatch(contractCall(contract, function, payload)
                                    .gas(2000000)
                                    .batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator));
        }

        @HapiTest
        @DisplayName("Batch contract call with 6kb payload")
        //  BATCH_04
        public Stream<DynamicTest> contractCallTxnSizeLimit() {
            final var contract = "CalldataSize";
            final var function = "callme";
            // Adjust the payload size with 512 bytes, so the total size is just under 6kb
            final var payload = new byte[MAX_CALL_DATA_SIZE - 1000];
            final var batchOperator = "batchOperator";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    uploadInitCode(contract),
                    contractCreate(contract),
                    atomicBatch(contractCall(contract, function, payload).batchKey(batchOperator))
                            .payingWith(batchOperator));
        }

        @HapiTest
        @DisplayName("Following batch with same inner txn")
        //  BATCH_06
        public Stream<DynamicTest> followingBatchWithSameButNonExecutedTxn() {
            final var payer = "payer";
            final var firstTxnId = "firstTxnId";
            final var secondTxnId = "secondTxnId";
            final var batchOperator = "batchOperator";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(firstTxnId).payerId(payer),
                    usableTxnIdNamed(secondTxnId).payerId(payer),
                    // execute first transaction
                    cryptoCreate("foo").txnId(firstTxnId).payingWith(payer),
                    // create a failing batch, containing duplicated transaction
                    atomicBatch(
                                    cryptoCreate("foo")
                                            .txnId(firstTxnId)
                                            .payingWith(payer)
                                            .batchKey(batchOperator)
                                            // defer status resolution, as the transaction should not be dispatched
                                            .deferStatusResolution(),
                                    // second inner txn should not be executed
                                    cryptoCreate("bar")
                                            .txnId(secondTxnId)
                                            .payingWith(payer)
                                            .batchKey(batchOperator)
                                            // defer status resolution, as the transaction should not be dispatched
                                            .deferStatusResolution())
                            .signedByPayerAnd(batchOperator)
                            .hasPrecheck(DUPLICATE_TRANSACTION),
                    // create a successful batch containing the second (non-executed) transaction
                    atomicBatch(cryptoCreate("bar")
                                    .txnId(secondTxnId)
                                    .payingWith(payer)
                                    .batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator));
        }

        @HapiTest
        @DisplayName("Deleted account key as batch key")
        //  BATCH_07
        public Stream<DynamicTest> deletedAccountKeyAsBatchKey() {
            final var payer = "payer";
            final var aliceKey = "aliceKey";
            final var alice = "alice";
            return hapiTest(
                    cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(aliceKey),
                    cryptoCreate(alice).key(aliceKey),
                    cryptoDelete(alice),
                    atomicBatch(cryptoCreate("foo").batchKey(aliceKey))
                            .payingWith(payer)
                            .signedBy(payer, aliceKey));
        }

        @HapiTest
        @DisplayName("Sign batch with additional keys")
        //  BATCH_09
        public Stream<DynamicTest> signBatchWithAdditionalKeys() {
            final var payer = "payer";
            final var alice = "alice";
            return hapiTest(
                    cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(alice),
                    atomicBatch(cryptoCreate("foo").batchKey(alice))
                            .payingWith(payer)
                            .signedBy(payer, alice));
        }
    }

    @Nested()
    @DisplayName("Signatures - positive")
    class AtomicBatchSignaturesPositive {

        @HapiTest
        // BATCH_18  BATCH_19
        @DisplayName("Batch should finalize hollow account")
        final Stream<DynamicTest> batchFinalizeHollowAccount() {
            final var alias = "alias";
            final var alias2 = "alias2";
            final var batchOperator = "batchOperator";
            return hapiTest(flattened(
                    cryptoCreate(batchOperator),
                    cryptoCreate("payer").balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(alias).shape(SECP_256K1_SHAPE),
                    newKeyNamed(alias2).shape(SECP_256K1_SHAPE),
                    createHollowAccountFrom(alias),
                    createHollowAccountFrom(alias2),
                    getAliasedAccountInfo(alias).isHollow(),
                    getAliasedAccountInfo(alias2).isHollow(),
                    atomicBatch(cryptoCreate("test")
                                    .payingWith("payer")
                                    .signedBy(batchOperator)
                                    .batchKey(batchOperator)
                                    .hasKnownStatus(INVALID_PAYER_SIGNATURE))
                            .payingWith(alias)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(alias))
                            .signedBy(alias, batchOperator)
                            // batch should fail after ingest, to be able to finalize hollow account
                            .hasPrecheck(INVALID_SIGNATURE),
                    getAliasedAccountInfo(alias)
                            .has(accountWith().hasEmptyKey())
                            .logged(),
                    atomicBatch(cryptoTransfer(tinyBarsFromTo("payer", batchOperator, ONE_HBAR))
                                    .payingWith("payer")
                                    .batchKey(batchOperator))
                            .payingWith(alias2)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(alias2))
                            .signedBy(alias2, batchOperator),
                    getAliasedAccountInfo(alias2)
                            .has(accountWith().hasNonEmptyKey())
                            .logged()));
        }

        @HapiTest
        @DisplayName("Failing batch should finalize hollow account")
        // BATCH_20
        final Stream<DynamicTest> failingBatchShouldFinalizeHollowAccount() {
            final var alias = "alias";
            final var batchOperator = "batchOperator";
            return hapiTest(flattened(
                    cryptoCreate("innerSender").balance(0L),
                    cryptoCreate("innerRecipient").balance(0L),
                    cryptoCreate(batchOperator),
                    newKeyNamed(alias).shape(SECP_256K1_SHAPE),
                    createHollowAccountFrom(alias),
                    getAliasedAccountInfo(alias).isHollow(),
                    // Atomic batch should fail after ingest, to be able to finalize hollow account
                    atomicBatch(cryptoTransfer(tinyBarsFromTo("innerSender", "innerRecipient", 123L))
                                    // Use sender with zero balance
                                    .payingWith(DEFAULT_PAYER)
                                    .hasKnownStatus(INSUFFICIENT_ACCOUNT_BALANCE)
                                    .batchKey(batchOperator))
                            .payingWith(alias)
                            .sigMapPrefixes(uniqueWithFullPrefixesFor(alias))
                            .signedBy(alias, batchOperator)
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),
                    getAliasedAccountInfo(alias).isNotHollow(),
                    getAccountRecords("innerRecipient")));
        }

        @HapiTest
        // BATCH_23
        @DisplayName("Threshold batch key should work")
        final Stream<DynamicTest> thresholdBatchKeyShouldWork() {
            final KeyShape threshKeyShape = KeyShape.threshOf(1, PREDEFINED_SHAPE, PREDEFINED_SHAPE);
            final var threshBatchKey = "threshBatchKey";
            final var alis = "alis";
            final var bob = "bob";

            return hapiTest(
                    cryptoCreate(alis).balance(FIVE_HBARS),
                    cryptoCreate(bob),
                    newKeyNamed(threshBatchKey).shape(threshKeyShape.signedWith(sigs(alis, bob))),
                    atomicBatch(
                                    cryptoCreate("foo").batchKey(threshBatchKey),
                                    cryptoCreate("bar").batchKey(threshBatchKey))
                            .signedByPayerAnd(alis));
        }

        @HapiTest
        // BATCH_25 BATCH_28 BATCH_29 BATCH_30
        // This cases all are very similar and can be combined into one
        @DisplayName("Payer is different from batch operator")
        final Stream<DynamicTest> payWithDifferentAccount() {
            final var alis = "alis";
            final var bob = "bob";

            return hapiTest(
                    cryptoCreate(alis).balance(FIVE_HBARS),
                    cryptoCreate(bob),
                    atomicBatch(
                                    cryptoCreate("foo").batchKey(bob),
                                    cryptoCreate("bar").batchKey(bob))
                            .payingWith(alis)
                            .signedBy(alis, bob));
        }

        @HapiTest
        final Stream<DynamicTest> multipleBatchKeysRequireAllBatchSigs() {
            final var opKey = "opKey";
            final var opAcct = "opAcct";
            final var anotherKey = "randomKey";

            return hapiTest(
                    newKeyNamed(opKey),
                    newKeyNamed(anotherKey),
                    cryptoCreate(opAcct).key(opKey).balance(ONE_HUNDRED_HBARS),
                    atomicBatch(
                                    oneHbarToDefaultPayerFrom(opAcct).batchKey(opKey),
                                    oneHbarToDefaultPayerFrom(opAcct).batchKey(anotherKey))
                            .payingWith(opAcct)
                            // Isn't signed by anotherKey, so should fail
                            .signedBy(opKey)
                            .hasPrecheck(INVALID_SIGNATURE),
                    atomicBatch(
                                    oneHbarToDefaultPayerFrom(opAcct).batchKey(opKey),
                                    oneHbarToDefaultPayerFrom(opAcct).batchKey(anotherKey))
                            .payingWith(opAcct)
                            // Isn't signed by opKey, so should fail
                            .signedBy(anotherKey)
                            .hasPrecheck(INVALID_SIGNATURE),
                    atomicBatch(
                                    oneHbarToDefaultPayerFrom(opAcct).batchKey(opKey),
                                    oneHbarToDefaultPayerFrom(opAcct).batchKey(anotherKey))
                            .payingWith(opAcct)
                            .signedBy(opKey, anotherKey)
                            .hasKnownStatus(SUCCESS));
        }

        private static HapiCryptoTransfer oneHbarToDefaultPayerFrom(final String payer) {
            return cryptoTransfer(movingHbar(1).between(payer, DEFAULT_PAYER)).payingWith(payer);
        }
    }

    @Nested
    @DisplayName("Privileged Transactions - POSITIVE")
    class PrivilegedTransactionsPositive {

        @LeakyHapiTest(requirement = {THROTTLE_OVERRIDES})
        @DisplayName("Batch containing only privileged transactions")
        public Stream<DynamicTest> batchContainingOnlyPrivilegedTxn() {
            final var batchOperator = "batchOperator";

            return hapiTest(
                    cryptoCreate(batchOperator).balance(FIVE_HBARS),
                    atomicBatch(fileUpdate(THROTTLE_DEFS)
                                    .batchKey(batchOperator)
                                    .noLogging()
                                    .payingWith(GENESIS)
                                    .contents(protoDefsFromResource("testSystemFiles/mainnet-throttles.json")
                                            .toByteArray()))
                            .payingWith(batchOperator));
        }
    }

    @Nested
    @DisplayName("Fees - POSITIVE")
    class FeesPositive {

        @HapiTest
        @DisplayName("Payer was charged for all transactions")
        public Stream<DynamicTest> payerWasCharged() {
            final var batchOperator = "batchOperator";
            return hapiTest(
                    cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                    atomicBatch(
                                    cryptoCreate("foo")
                                            .via("innerTxn1")
                                            .batchKey(batchOperator)
                                            .payingWith(batchOperator),
                                    cryptoCreate("bar")
                                            .via("innerTxn2")
                                            .batchKey(batchOperator)
                                            .payingWith(batchOperator))
                            .payingWith(batchOperator)
                            .via("batchTxn"),
                    // validate the fee charged for the batch txn and the inner txns
                    validateChargedUsd("batchTxn", 0.001),
                    validateInnerTxnChargedUsd("innerTxn1", "batchTxn", 0.05, 5),
                    validateInnerTxnChargedUsd("innerTxn2", "batchTxn", 0.05, 5));
        }

        @Nested
        @DisplayName("Fees remain the same inside of batch")
        class FeesRemainSameInsideOfBatch {

            static final String OPERATOR = "batchOperator";
            static final String OWNER = "owner";
            static final String SPENDER = "spender";
            static final String SPENDER_2 = "spender2";
            static final String MEMO =
                    "Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo Memo";

            @HapiTest
            @DisplayName("Compare base fee")
            public Stream<DynamicTest> compareBaseFee() {
                final AtomicLong expectedFee = new AtomicLong();
                final AtomicLong feeInBatch = new AtomicLong();
                return hapiTest(
                        cryptoCreate(OPERATOR),
                        cryptoCreate(OWNER),
                        cryptoCreate(SPENDER),
                        cryptoCreate(SPENDER_2),
                        atomicBatch(cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                                        .blankMemo()
                                        .batchKey(OPERATOR)
                                        .via("cryptoAllowanceInBatch"))
                                .payingWith(OPERATOR),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER_2, 100L)
                                .blankMemo()
                                .via("cryptoAllowance"),
                        getTxnRecord("cryptoAllowance")
                                .loggingOnlyFee()
                                .exposingTo(r -> expectedFee.set(r.getTransactionFee())),
                        getTxnRecord("cryptoAllowanceInBatch")
                                .loggingOnlyFee()
                                .exposingTo(r -> feeInBatch.set(r.getTransactionFee())),
                        verifyEqualFees(expectedFee, feeInBatch, 0.5));
            }

            @HapiTest
            @DisplayName("Compare fee when transaction memo is set")
            public Stream<DynamicTest> compareFeeWithMemo() {
                final AtomicLong expectedFee = new AtomicLong();
                final AtomicLong feeInBatch = new AtomicLong();
                return hapiTest(
                        cryptoCreate(OPERATOR),
                        cryptoCreate(OWNER),
                        cryptoCreate(SPENDER),
                        cryptoCreate(SPENDER_2),
                        atomicBatch(cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                                        .memo(MEMO)
                                        .batchKey(OPERATOR)
                                        .via("cryptoAllowanceInBatch"))
                                .payingWith(OPERATOR),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER_2, 100L)
                                .memo(MEMO)
                                .via("cryptoAllowance"),
                        getTxnRecord("cryptoAllowance")
                                .loggingOnlyFee()
                                .exposingTo(r -> expectedFee.set(r.getTransactionFee())),
                        getTxnRecord("cryptoAllowanceInBatch")
                                .loggingOnlyFee()
                                .exposingTo(r -> feeInBatch.set(r.getTransactionFee())),
                        verifyEqualFees(expectedFee, feeInBatch, 0.5));
            }

            @HapiTest
            @DisplayName("Compare fee when payer's key is a list")
            public Stream<DynamicTest> compareWithPayersKeyList() {
                final AtomicLong expectedFee = new AtomicLong();
                final AtomicLong feeInBatch = new AtomicLong();
                return hapiTest(
                        cryptoCreate(OPERATOR),
                        cryptoCreate(OWNER),
                        cryptoCreate(SPENDER),
                        cryptoCreate(SPENDER_2),
                        newKeyNamed("ownerKey").shape(SigControl.threshSigs(3, ON, ON, ON, ON, ON, ON, ON)),
                        cryptoUpdate(OWNER).key("ownerKey"),
                        atomicBatch(cryptoApproveAllowance()
                                        .payingWith(OWNER)
                                        .addCryptoAllowance(OWNER, SPENDER, 100L)
                                        .memo(MEMO)
                                        .batchKey(OPERATOR)
                                        .via("cryptoAllowanceInBatch"))
                                .payingWith(OPERATOR),
                        cryptoApproveAllowance()
                                .payingWith(OWNER)
                                .addCryptoAllowance(OWNER, SPENDER_2, 100L)
                                .memo(MEMO)
                                .via("cryptoAllowance"),
                        getTxnRecord("cryptoAllowance")
                                .loggingOnlyFee()
                                .exposingTo(r -> expectedFee.set(r.getTransactionFee())),
                        getTxnRecord("cryptoAllowanceInBatch")
                                .loggingOnlyFee()
                                .exposingTo(r -> feeInBatch.set(r.getTransactionFee())),
                        verifyEqualFees(expectedFee, feeInBatch, 0.5));
            }

            private RunnableOp verifyEqualFees(AtomicLong expectedFee, AtomicLong feeInBatch, double percentDeviation) {
                return verify(() -> assertEquals(
                        expectedFee.get(),
                        feeInBatch.get(),
                        expectedFee.get() * percentDeviation / 100,
                        () -> String.format(
                                "Expected in-batch fee to be <%d +/- 5%%>, was" + " <%d>!",
                                expectedFee.get(), feeInBatch.get())));
            }
        }
    }

    @Nested
    @DisplayName("Batch Order And Execution - POSITIVE")
    class BatchOrderExecutionPositive {

        @HapiTest
        @DisplayName("Validate batch valid start")
        // BATCH_15
        final Stream<DynamicTest> validateBatchValidStart() {
            final var payer = "payer";
            final var batchTxnId = "batchTxnId";
            final var innerTxnId = "innerTxnId";
            final var beforeHour = -3_600L; // 1 hour in the past
            final var batchOperator = "batchOperator";

            return hapiTest(
                    cryptoCreate(batchOperator),
                    cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),

                    // modify batch valid start to 1 hour in the past
                    usableTxnIdNamed(batchTxnId).modifyValidStart(beforeHour).payerId(payer),
                    usableTxnIdNamed(innerTxnId).payerId(payer),
                    atomicBatch(cryptoCreate("foo")
                                    .txnId(innerTxnId)
                                    .payingWith(payer)
                                    .batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator)
                            .txnId(batchTxnId)
                            .payingWith(payer)
                            .hasPrecheck(TRANSACTION_EXPIRED),

                    // submit new batch with valid start and the same inner transaction
                    atomicBatch(cryptoCreate("foo")
                                    .txnId(innerTxnId)
                                    .payingWith(payer)
                                    .batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator));
        }

        @HapiTest
        @DisplayName("Validate inner txn valid start")
        // BATCH_16
        final Stream<DynamicTest> validateInnerTxnValidStart() {
            final var alice = "alice";
            final var bob = "bob";
            final var dave = "dave";
            final var carl = "carl";

            final var bobInnerTxnId = "bobInnerTxnId";
            final var bobExpiredTxnId = "bobExpiredTxnId";
            final var daveInnerTxnId = "daveInnerTxnId";
            final var carlInnerTxnId = "carlInnerTxnId";

            final var beforeHour = -3_600L; // 1 hour in the past

            return hapiTest(
                    cryptoCreate(alice).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(bob).balance(ONE_HBAR),
                    cryptoCreate(dave).balance(ONE_HBAR),
                    cryptoCreate(carl).balance(ONE_HBAR),
                    usableTxnIdNamed(bobExpiredTxnId)
                            .modifyValidStart(beforeHour)
                            .payerId(bob),
                    usableTxnIdNamed(bobInnerTxnId).payerId(bob),
                    usableTxnIdNamed(daveInnerTxnId).payerId(dave),
                    usableTxnIdNamed(carlInnerTxnId).payerId(carl),
                    atomicBatch(
                                    // Bob's txn is expired, so no inner txns should be executed
                                    cryptoCreate("foo")
                                            .batchKey(alice)
                                            .balance(1L)
                                            .txnId(bobExpiredTxnId)
                                            .payingWith(bob)
                                            .hasKnownStatus(TRANSACTION_EXPIRED),
                                    cryptoCreate("bar")
                                            .batchKey(alice)
                                            .balance(1L)
                                            .txnId(daveInnerTxnId)
                                            .payingWith(dave),
                                    cryptoCreate("baz")
                                            .batchKey(alice)
                                            .balance(1L)
                                            .txnId(carlInnerTxnId)
                                            .payingWith(carl))
                            .signedByPayerAnd(alice)
                            .hasPrecheck(TRANSACTION_EXPIRED),
                    atomicBatch(
                                    cryptoCreate("foo")
                                            .batchKey(alice)
                                            .balance(1L)
                                            .txnId(bobInnerTxnId)
                                            .payingWith(bob),
                                    cryptoCreate("bar")
                                            .batchKey(alice)
                                            .balance(1L)
                                            .txnId(daveInnerTxnId)
                                            .payingWith(dave),
                                    cryptoCreate("baz")
                                            .batchKey(alice)
                                            .balance(1L)
                                            .txnId(carlInnerTxnId)
                                            .payingWith(carl))
                            .signedByPayerAnd(alice),

                    // validate inner transactions were successfully executed
                    getAccountBalance("foo").hasTinyBars(1L),
                    getAccountBalance("bar").hasTinyBars(1L),
                    getAccountBalance("baz").hasTinyBars(1L));
        }

        @HapiTest
        @DisplayName("Submit batch containing Hapi and Ethereum txns")
        // BATCH_17
        final Stream<DynamicTest> submitBatchWithEthereumTxn() {
            final var receiver = "receiver";
            final var batchOperator = "batchOperator";
            return hapiTest(
                    cryptoCreate(batchOperator),
                    newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                    cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                    withOpContext((spec, opLog) -> updateSpecFor(spec, SECP_256K1_SOURCE_KEY)),
                    cryptoCreate(receiver).balance(0L),
                    // submit a batch with Hapi and Ethereum txns
                    atomicBatch(
                                    cryptoTransfer(tinyBarsFromTo(GENESIS, receiver, FIVE_HBARS))
                                            .batchKey(batchOperator),
                                    ethereumCryptoTransfer(receiver, FIVE_HBARS)
                                            .type(EthTxData.EthTransactionType.EIP2930)
                                            .payingWith(SECP_256K1_SOURCE_KEY)
                                            .nonce(0)
                                            .gasPrice(0L)
                                            .gasLimit(2_000_000L)
                                            .batchKey(batchOperator))
                            .signedByPayerAnd(batchOperator),
                    getAccountBalance(receiver).hasTinyBars(FIVE_HBARS * 2));
        }
    }

    @Nested
    @DisplayName("Validate usedGas amount for Precompile calls")
    class ValidatePrecompileGasUsedForInnerTxnChildren {

        @HapiTest
        @DisplayName("Validate mint precompile gas used for inner transaction")
        final Stream<DynamicTest> validateInnerCallToMintPrecompile() {
            final var nft = "nft";
            final var gasToOffer = 2_000_000L;
            final var mintContract = "MintContract";
            final var supplyKey = "supplyKey";
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();
            final KeyShape listOfPredefinedAndContract = KeyShape.threshOf(1, PREDEFINED_SHAPE, CONTRACT);
            final AtomicLong gasUsed = new AtomicLong(0);
            final var nftMetadata = (Object) new byte[][] {genRandomBytes(100)};
            return hapiTest(
                    cryptoCreate(ALICE).balance(10 * ONE_HUNDRED_HBARS),
                    tokenCreate(nft)
                            .tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .supplyKey(ALICE)
                            .adminKey(ALICE)
                            .treasury(ALICE)
                            .exposingAddressTo(tokenAddress::set),
                    uploadInitCode(mintContract),
                    sourcing(() -> contractCreate(mintContract, tokenAddress.get())
                            .payingWith(ALICE)
                            .gas(gasToOffer)),
                    newKeyNamed(supplyKey).shape(listOfPredefinedAndContract.signedWith(sigs(ALICE, mintContract))),
                    tokenUpdate(nft).supplyKey(supplyKey).signedByPayerAnd(ALICE),

                    // mint NFT via precompile and save the used gas
                    contractCall(mintContract, "mintNonFungibleToken", nftMetadata)
                            .payingWith(ALICE)
                            .alsoSigningWithFullPrefix(supplyKey)
                            .gas(gasToOffer)
                            .via("mint"),

                    // save precompile gas used
                    withOpContext((spec, op) -> {
                        final var callRecord = getTxnRecord("mint").andAllChildRecords();
                        allRunFor(spec, callRecord);
                        gasUsed.set(callRecord
                                .getFirstNonStakingChildRecord()
                                .getContractCallResult()
                                .getGasUsed());
                    }),

                    // mint NFT via precompile as inner batch txn
                    atomicBatch(contractCall(mintContract, "mintNonFungibleToken", nftMetadata)
                                    .batchKey(ALICE)
                                    .payingWith(ALICE)
                                    .alsoSigningWithFullPrefix(supplyKey)
                                    .gas(gasToOffer)
                                    .via("mintFromBatch"))
                            .payingWith(ALICE),

                    // validate precompile used gas is the same as in the previous call
                    sourcing(() -> childRecordsCheck(
                            "mintFromBatch",
                            SUCCESS,
                            recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().gasUsed(gasUsed.get())))));
        }

        @HapiTest
        @DisplayName("Validate associate precompile gas used for inner transaction")
        final Stream<DynamicTest> validateInnerCallToAssociatePrecompile() {
            final var account = "account";
            final var account2 = "account2";
            final var gasToOffer = 2_000_000L;
            final var associateContract = "AssociateDissociate";
            final AtomicLong gasUsed = new AtomicLong(0);
            final KeyShape simpleContractKeyShape = KeyShape.threshOf(1, KeyShape.SIMPLE, CONTRACT);

            final AtomicReference<Address> accountAddress = new AtomicReference<>();
            final AtomicReference<Address> account2Address = new AtomicReference<>();
            final AtomicReference<Address> tokenAddress = new AtomicReference<>();

            return hapiTest(
                    // deploy the contract
                    uploadInitCode(associateContract),
                    contractCreate(associateContract).gas(gasToOffer),

                    // create account and token with proper keys and expose their addresses
                    newKeyNamed("key").shape(simpleContractKeyShape.signedWith(sigs(ON, associateContract))),
                    cryptoCreate(account)
                            .key("key")
                            .balance(ONE_HUNDRED_HBARS)
                            .exposingEvmAddressTo(accountAddress::set),
                    cryptoCreate(account2)
                            .key("key")
                            .balance(ONE_HUNDRED_HBARS)
                            .exposingEvmAddressTo(account2Address::set),
                    cryptoCreate("treasury"),
                    tokenCreate("token")
                            .tokenType(FUNGIBLE_COMMON)
                            .treasury("treasury")
                            .exposingAddressTo(tokenAddress::set),
                    cryptoCreate("operator"),

                    // associate call
                    sourcing(() -> contractCall(
                                    associateContract, "tokenAssociate", accountAddress.get(), tokenAddress.get())
                            .gas(gasToOffer)
                            .via("associateTxn")),

                    // save precompile gas used
                    withOpContext((spec, op) -> {
                        final var callRecord = getTxnRecord("associateTxn")
                                .andAllChildRecords()
                                .logged();
                        allRunFor(spec, callRecord);
                        gasUsed.set(callRecord
                                .getFirstNonStakingChildRecord()
                                .getContractCallResult()
                                .getGasUsed());
                    }),

                    // associate via precompile as inner batch txn
                    sourcing(() -> atomicBatch(contractCall(
                                            associateContract,
                                            "tokenAssociate",
                                            account2Address.get(),
                                            tokenAddress.get())
                                    .batchKey("operator")
                                    .gas(gasToOffer)
                                    .via("associateFromBatch"))
                            .payingWith("operator")),

                    // validate precompile used gas is the same as in the previous call
                    sourcing(() -> childRecordsCheck(
                            "associateFromBatch",
                            SUCCESS,
                            recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().gasUsed(gasUsed.get())))));
        }

        @HapiTest
        @DisplayName("Validate crypto transfer precompile gas used for inner transaction")
        @Tag(MATS)
        final Stream<DynamicTest> validateInnerCallToCryptoTransferPrecompile() {
            final var sender = "sender";
            final var receiver = "receiver";
            final var gasToOffer = 2_000_000L;
            final var transferContract = "AtomicCryptoTransfer";
            final AtomicLong gasUsed = new AtomicLong(0);
            final KeyShape simpleContractKeyShape = KeyShape.threshOf(1, KeyShape.SIMPLE, CONTRACT);

            final AtomicReference<Address> senderAddress = new AtomicReference<>();
            final AtomicReference<Address> receiverAddress = new AtomicReference<>();

            // call parameters
            final Supplier<Tuple> transferListSupplier = () -> transferList()
                    .withAccountAmounts(
                            accountAmount(senderAddress.get(), -ONE_HBAR, false),
                            accountAmount(receiverAddress.get(), ONE_HBAR, false))
                    .build();
            final var EMPTY_TUPLE_ARRAY = new Tuple[] {};

            return hapiTest(
                    // deploy the contract
                    uploadInitCode(transferContract),
                    contractCreate(transferContract).gas(gasToOffer),

                    // create sender and receiver with proper keys and expose their addresses
                    newKeyNamed("key").shape(simpleContractKeyShape.signedWith(sigs(ON, transferContract))),
                    cryptoCreate(sender).key("key").balance(ONE_HUNDRED_HBARS).exposingEvmAddressTo(senderAddress::set),
                    cryptoCreate(receiver).key("key").balance(0L).exposingEvmAddressTo(receiverAddress::set),
                    cryptoCreate("operator"),

                    // Simple transfer between sender, receiver
                    sourcing(() -> contractCall(
                                    transferContract,
                                    "transferMultipleTokens",
                                    transferListSupplier.get(),
                                    EMPTY_TUPLE_ARRAY)
                            .via("cryptoTransferTxn")
                            .gas(gasToOffer)),

                    // save precompile gas used
                    withOpContext((spec, op) -> {
                        final var callRecord = getTxnRecord("cryptoTransferTxn")
                                .andAllChildRecords()
                                .logged();
                        allRunFor(spec, callRecord);
                        gasUsed.set(callRecord
                                .getFirstNonStakingChildRecord()
                                .getContractCallResult()
                                .getGasUsed());
                    }),

                    // transfer hbars via precompile as inner batch txn
                    sourcing(() -> atomicBatch(contractCall(
                                            transferContract,
                                            "transferMultipleTokens",
                                            transferListSupplier.get(),
                                            EMPTY_TUPLE_ARRAY)
                                    .batchKey("operator")
                                    .via("cryptoTransferFromBatch")
                                    .gas(gasToOffer))
                            .payingWith("operator")),

                    // validate precompile used gas is the same as in the previous call
                    sourcing(() -> childRecordsCheck(
                            "cryptoTransferFromBatch",
                            SUCCESS,
                            recordWith()
                                    .status(SUCCESS)
                                    .contractCallResult(resultWith().gasUsed(gasUsed.get())))));
        }
    }

    @HapiTest
    @DisplayName("Updated required key in batch")
    public Stream<DynamicTest> useUpdatedKeyInBatch() {
        final var batchOperator = "batchOperator";

        return hapiTest(
                newKeyNamed("adminKey"),
                newKeyNamed("newAdminKey"),
                newKeyNamed("kycKey"),
                newKeyNamed("newKycKey"),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("treasury"),
                tokenCreate("testToken")
                        .initialSupply(1000L)
                        .treasury("treasury")
                        .kycKey("kycKey")
                        .supplyKey("treasury")
                        .adminKey("adminKey"),
                atomicBatch(
                                // update admin key
                                tokenUpdate("testToken")
                                        .adminKey("newAdminKey")
                                        .batchKey(batchOperator)
                                        .payingWith(batchOperator)
                                        .signedBy("adminKey", "newAdminKey", batchOperator),

                                // update kyc key
                                tokenUpdate("testToken")
                                        .kycKey("newKycKey")
                                        .batchKey(batchOperator)
                                        .payingWith(batchOperator)
                                        .via("updateKyc")
                                        .sigMapPrefixes(uniqueWithFullPrefixesFor("newAdminKey", batchOperator))
                                        .signedBy("newAdminKey", batchOperator))
                        .payingWith(batchOperator)
                        .hasKnownStatus(SUCCESS));
    }

    @HapiTest
    @DisplayName("Additional verifications scale as expected")
    public Stream<DynamicTest> additionalVerificationsScaleAsExpected() {
        final var overlappingKeyGen = OverlappingKeyGenerator.withAtLeastOneOverlappingByte(3);
        final var feeOneSigMint = new AtomicLong();
        final var feeTwoSigMint = new AtomicLong();
        final var feeInBatchMint = new AtomicLong();
        return hapiTest(
                newKeyNamed("aKey").generator(overlappingKeyGen),
                newKeyNamed("bKey").generator(overlappingKeyGen),
                newKeyNamed("cKey").generator(overlappingKeyGen),
                newKeyListNamed("threeUnique", List.of("aKey", "bKey", "cKey", "bKey", "aKey")),
                cryptoCreate("minter").key("aKey").balance(ONE_HUNDRED_HBARS),
                cryptoCreate("batchOperator").balance(ONE_HUNDRED_HBARS),
                tokenCreate("token").treasury(DEFAULT_PAYER).supplyKey("aKey").adminKey("aKey"),
                mintToken("token", 123)
                        .sigMapPrefixes(withNature(FULL_PREFIXES))
                        .via("oneSigMint")
                        .payingWith("minter"),
                mintToken("token", 123)
                        .via("twoSigsMint")
                        .sigMapPrefixes(withNature(FULL_PREFIXES))
                        .via("twoSigsMint")
                        .payingWith("minter")
                        .signedBy("minter", DEFAULT_PAYER),
                getTxnRecord("oneSigMint").loggingOnlyFee().exposingTo(r -> feeOneSigMint.set(r.getTransactionFee())),
                getTxnRecord("twoSigsMint").loggingOnlyFee().exposingTo(r -> feeTwoSigMint.set(r.getTransactionFee())),
                atomicBatch(
                                tokenUpdate("token")
                                        .supplyKey("threeUnique")
                                        .batchKey("batchOperator")
                                        .payingWith("minter")
                                        .signedBy("aKey", "bKey", "cKey"),
                                mintToken("token", 456)
                                        .batchKey("batchOperator")
                                        .via("threeSigsMint")
                                        .payingWith("minter")
                                        .signedBy("aKey", "bKey", "cKey"))
                        .payingWith("batchOperator"),
                getTxnRecord("threeSigsMint").exposingTo(r -> feeInBatchMint.set(r.getTransactionFee())),
                // Assert the batch fee is within 10% of what we would roughly by scaling up the one-sig
                // mint fee by the twice increase in fee for the two-sig mint
                verify(() -> {
                    long oneSigDiff = feeTwoSigMint.get() - feeOneSigMint.get();
                    long approxThreeSig = feeOneSigMint.get() + 2 * oneSigDiff;
                    long allowedDeviation = approxThreeSig / 10;
                    org.junit.jupiter.api.Assertions.assertTrue(
                            Math.abs(feeInBatchMint.get() - approxThreeSig) <= allowedDeviation,
                            () -> String.format(
                                    "Expected in-batch mint fee to be <%d +/- 5%%>, was" + " <%d>!",
                                    approxThreeSig, feeInBatchMint.get()));
                }));
    }

    @HapiTest
    @Tag(MATS)
    final Stream<DynamicTest> payerIsContract() {
        return hapiTest(
                cryptoCreate("sender").balance(ONE_MILLION_HBARS),
                cryptoCreate("receiver"),
                cryptoCreate("batchOperator"),
                uploadInitCode("Multipurpose"),
                contractCreate("Multipurpose"),
                atomicBatch(cryptoTransfer(movingHbar(1).between("sender", "receiver"))
                                .batchKey("batchOperator")
                                .payingWith("Multipurpose"))
                        .payingWith("batchOperator")
                        .hasPrecheck(PAYER_ACCOUNT_NOT_FOUND));
    }

    @HapiTest
    final Stream<DynamicTest> deletedPayer() {
        return hapiTest(
                cryptoCreate("batchOperator"),
                cryptoCreate("sender").balance(ONE_MILLION_HBARS),
                cryptoCreate("receiver"),
                cryptoCreate("payer"),
                cryptoDelete("payer"),
                atomicBatch(cryptoTransfer(movingHbar(1).between("sender", "receiver"))
                                .batchKey("batchOperator")
                                .payingWith("payer")
                                .signedBy("payer", "sender"))
                        .payingWith("batchOperator")
                        .via("batch")
                        .hasPrecheck(PAYER_ACCOUNT_DELETED));
    }

    @HapiTest
    Stream<DynamicTest> innerTxnIdTooFarInFutureFailsBatch() {
        final var civilian = "civilian";
        final var batchOperator = "batchOperator";
        final var now = Instant.now();

        return hapiTest(
                cryptoCreate(civilian).balance(ONE_HBAR),
                cryptoCreate(batchOperator).balance(ONE_HUNDRED_HBARS),
                usableTxnIdNamed("batchTxn")
                        .modifyValidStart(now.getEpochSecond())
                        .payerId(batchOperator),
                usableTxnIdNamed("validInner").payerId(civilian).modifyValidStart(now.getEpochSecond() + 60L),
                usableTxnIdNamed("invalidInner").payerId(civilian).modifyValidStart(now.getEpochSecond() + 300L),
                atomicBatch(
                                // The valid inner transaction, only 60 seconds after batch valid start, is fine
                                cryptoTransfer(movingHbar(1).between(civilian, batchOperator))
                                        .batchKey(batchOperator)
                                        .txnId("validInner")
                                        .payingWith(civilian)
                                        .hasPrecheck(OK),
                                // But the invalid inner transaction, 5 minutes after batch valid start, is too late
                                cryptoTransfer(movingHbar(1).between(civilian, batchOperator))
                                        .batchKey(batchOperator)
                                        .txnId("invalidInner")
                                        .payingWith(civilian))
                        .txnId("batchTxn")
                        .hasPrecheck(INVALID_TRANSACTION_START));
    }
}
