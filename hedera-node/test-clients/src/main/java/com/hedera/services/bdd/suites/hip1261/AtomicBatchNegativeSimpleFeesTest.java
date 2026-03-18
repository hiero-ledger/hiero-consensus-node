// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.ATOMIC_BATCH;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.burnToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.deleteTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.ethereumCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromAccountToAlias;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateInnerTxnChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SECP_256K1_SHAPE;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedAtomicBatchFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileAppendFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenBurnFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicSubmitMessageFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateInnerChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_LIST_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FILE_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALIAS_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MAX_FILE_SIZE_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_MAX_SUPPLY_REACHED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.WRONG_NONCE;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.STATE_BYTES;
import static org.hiero.hapi.support.fees.Extra.TOKEN_TYPES;

import com.hedera.node.app.hapi.utils.ethereum.EthTxData;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class AtomicBatchNegativeSimpleFeesTest {
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String PAYER = "payer";
    private static final String PAYER_INSUFFICIENT_BALANCE = "payerInsufficientBalance";
    private static final String OWNER = "owner";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_NOT_ASSOCIATED = "receiverNotAssociated";
    private static final String adminKey = "adminKey";
    private static final String submitKey = "submitKey";
    private static final String supplyKey = "supplyKey";
    private static final String freezeKey = "freezeKey";
    private static final String pauseKey = "pauseKey";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String RELAYER = "relayer";
    private static final String CONTRACT = "SmartContractsFees";
    private static final String SECP_256K1_SOURCE_KEY = "secp256K1SourceKey";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("Batch-Level Scenarios - Failures on Ingest")
    class BatchLevelScenariosForFailuresOnIngest {
        @HapiTest
        @DisplayName("Batch Transaction with Invalid Signature Fails on Ingest")
        final Stream<DynamicTest> batchTransactionWithInvalidSignatureFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Batch Transaction with Insufficient Transaction Fee Fails on Ingest")
        final Stream<DynamicTest> batchTransactionWithInsufficientTxnFeeFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch(cryptoCreate("test")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .via("batchTxn")
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .fee(ONE_HBAR / 1000L)
                            .via("batchTxn")
                            .hasPrecheck(INSUFFICIENT_TX_FEE),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Batch Transaction with Insufficient Batch Payer Balance Fails on Ingest")
        final Stream<DynamicTest> batchTransactionWithInsufficientBatchPayerBalanceFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch(cryptoCreate("test")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .via("batchTxn")
                            .payingWith(PAYER_INSUFFICIENT_BALANCE)
                            .signedBy(PAYER_INSUFFICIENT_BALANCE, BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Batch Transaction with Too Long Memo Fails on Ingest")
        final Stream<DynamicTest> batchTransactionWithTooLongMemoFailsOnIngest() {
            final var LONG_MEMO = "x".repeat(1025); // memo exceeds 1024 bytes limit
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .memo(LONG_MEMO)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(MEMO_TOO_LONG),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Expired Batch Transaction Fails on Ingest")
        final Stream<DynamicTest> expiredBatchTransactionFailsOnIngest() {
            final var expiredTxnId = "expiredTxn";
            final var oneHourPast = -3_600L; // 1 hour before
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    usableTxnIdNamed(expiredTxnId).modifyValidStart(oneHourPast).payerId(PAYER),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .txnId(expiredTxnId)
                            .via("batchTxn")
                            .hasPrecheck(TRANSACTION_EXPIRED),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Batch Transaction With Too Far Start Time Fails on Ingest")
        final Stream<DynamicTest> batchTransactionWithTooFarStartTimeFailsOnIngest() {
            final var invalidTxnStartId = "invalidTxnStart";
            final var oneHourPast = 3_600L; // 1 hour later
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    usableTxnIdNamed(invalidTxnStartId)
                            .modifyValidStart(oneHourPast)
                            .payerId(PAYER),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .txnId(invalidTxnStartId)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_TRANSACTION_START),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Batch Transaction With Invalid Duration Time Fails on Ingest")
        final Stream<DynamicTest> batchTransactionWithInvalidDurationTimeFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .validDurationSecs(0)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_TRANSACTION_DURATION),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Duplicate Batch Transaction Fails on Ingest")
        final Stream<DynamicTest> DuplicateBatchTransactionFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("initialBatchTxn"),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxn")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .txnId("initialBatchTxn")
                            .via("duplicateBatchTxn")
                            .hasPrecheck(DUPLICATE_TRANSACTION)));
        }

        @HapiTest
        @DisplayName("Empty Batch Transaction Fails on Ingest")
        final Stream<DynamicTest> emptyBatchTransactionFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    atomicBatch()
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(BATCH_LIST_EMPTY),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName("Batch Transaction with one failing on ingest inner txn - Fails on Ingest")
        final Stream<DynamicTest> batchTransactionWithOneFailingOnIngestInnerTxnFailsOnIngest() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(
                                    cryptoCreate("test")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxnCryptoCreate")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                            .payingWith(PAYER)
                                            .signedBy(BATCH_OPERATOR, OWNER)
                                            .via("innerTxnCryptoTransfer")
                                            .batchKey(BATCH_OPERATOR)
                                            .hasPrecheck(INVALID_SIGNATURE))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasPrecheck(INVALID_SIGNATURE),

                    // assert no txn record is created
                    getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                    getTxnRecord("innerTxnCryptoCreate").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                    getTxnRecord("innerTxnCryptoTransfer").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }
    }

    @Nested
    @DisplayName("Batch-Level Scenarios - Failures on Handle")
    class BatchLevelScenariosForFailuresOnHandle {
        @HapiTest
        @DisplayName(
                "Batch Transaction with last failing on handle inner txn with same inner and outer Payer - Fails on Handle - Full fees charged")
        final Stream<DynamicTest>
                batchTransactionWithLastFailingOnHandleInnerTxnWithSameInnerAndOuterPayerFailsOnHandle() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(
                                    cryptoCreate("test")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxnCryptoCreate")
                                            .batchKey(BATCH_OPERATOR),
                                    cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxnCryptoTransfer")
                                            .batchKey(BATCH_OPERATOR)
                                            .hasKnownStatus(INVALID_SIGNATURE))
                            .payingWith(PAYER)
                            .signedBy(PAYER, BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate outer batch fee charged
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    // validate inner transactions fees charged
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerTxnCryptoCreate",
                            "batchTxn",
                            txnSize -> expectedCryptoCreateFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerTxnCryptoTransfer",
                            "batchTxn",
                            txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName(
                "Batch Transaction with last failing on handle inner txn with different inner and outer Payer - Fails on Handle - Full fees charged")
        final Stream<DynamicTest>
                batchTransactionWithLastFailingOnHandleInnerTxnWithDifferentInnerAndOuterPayerFailsOnHandle() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxnCryptoTransfer")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn"),

                    // validate outer batch fee charged
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    // validate inner transactions fees charged
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerTxnCryptoTransfer",
                            "batchTxn",
                            txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1)));
        }

        @HapiTest
        @DisplayName(
                "Batch Transaction with first failing on handle inner txn - Fails on Handle - First inner txn charged only")
        final Stream<DynamicTest>
                batchTransactionWithFirstFailingOnHandleInnerTxnFirstInnerTxnChargedOnlyFailsOnHandle() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(
                                    cryptoTransfer(moving(101L, FUNGIBLE_TOKEN)
                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                            .payingWith(PAYER)
                                            .signedBy(PAYER, OWNER)
                                            .via("innerTxnCryptoTransfer")
                                            .batchKey(BATCH_OPERATOR)
                                            .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE),
                                    cryptoCreate("test")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER)
                                            .via("innerTxnCryptoCreate")
                                            .batchKey(BATCH_OPERATOR))
                            .payingWith(BATCH_OPERATOR)
                            .signedBy(BATCH_OPERATOR)
                            .via("batchTxn")
                            .hasKnownStatus(INNER_TRANSACTION_FAILED),

                    // validate outer batch fee charged
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    // validate inner transactions fees charged
                    validateInnerChargedUsdWithinWithTxnSize(
                            "innerTxnCryptoTransfer",
                            "batchTxn",
                            txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ACCOUNTS, 2L,
                                    TOKEN_TYPES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    // assert no inner txn record is created for the second txn
                    getTxnRecord("innerTxnCryptoCreate").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }

        @HapiTest
        @DisplayName(
                "Batch Transaction with missing Batch Operator signature - Fails on Handle - Full fees charged for Outer txn, no inner txn record")
        final Stream<DynamicTest> batchTransactionWithMissingBatchOperatorSignatureFailsOnHandleNoInnerTxnRecord() {
            return hapiTest(flattened(
                    createAccountsAndKeys(),
                    createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                    tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                    atomicBatch(cryptoTransfer(moving(10L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, OWNER)
                                    .via("innerTxnCryptoTransfer")
                                    .batchKey(BATCH_OPERATOR))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via("batchTxn")
                            .hasKnownStatus(INVALID_SIGNATURE),

                    // validate outer batch fee charged
                    validateChargedUsdWithinWithTxnSize(
                            "batchTxn",
                            txnSize -> expectedAtomicBatchFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    // assert no inner txn record is created
                    getTxnRecord("innerTxnCryptoTransfer").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
        }
    }

    @Nested
    @DisplayName("Service-Level Atomic Batch Scenarios")
    class ServiceLevelAtomicBatchScenarios {
        @Nested
        @DisplayName("File Service Atomic Batch Inner Failures")
        class FileServiceAtomicBatchInnerFailures {
            @HapiTest
            @DisplayName(
                    "File Update Inner Batch Transaction with invalid key signature - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> fileUpdateInnerBatchTransactionWithInvalidKeySignatureFailsOnHandle() {
                var contents = "0".repeat(1000).getBytes();
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyListNamed("WACL", List.of(OWNER)),
                        fileCreate("test")
                                .key("WACL")
                                .contents("original content")
                                .via("fileCreate"),
                        atomicBatch(fileUpdate("test")
                                        .contents(contents)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxnFileUpdate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_SIGNATURE))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner transactions fees charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFileUpdate",
                                "batchTxn",
                                txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, 1000L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName("File Update Inner Batch Transaction with non-existent - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> fileUpdateInnerBatchTransactionWithNonExistentFileFailsOnHandle() {
                var contents = "0".repeat(1000).getBytes();
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyListNamed("WACL", List.of(PAYER)),
                        atomicBatch(fileUpdate("0.0.999999")
                                        .contents(contents)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, "WACL")
                                        .via("innerTxnFileUpdate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_FILE_ID))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner transactions fees charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFileUpdate",
                                "batchTxn",
                                txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, 1000L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "File Update Inner Batch Transaction with already deleted file - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> fileUpdateInnerBatchTransactionWithDeletedFileFailsOnHandle() {
                var contents = "0".repeat(1000).getBytes();
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyListNamed("WACL", List.of(PAYER)),
                        fileCreate("test")
                                .key("WACL")
                                .contents("original content")
                                .via("fileCreate"),
                        fileDelete("test")
                                .payingWith(PAYER)
                                .signedBy(PAYER, "WACL")
                                .via("fileDelete"),
                        atomicBatch(fileUpdate("test")
                                        .contents(contents)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, "WACL")
                                        .via("innerTxnFileUpdate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(FILE_DELETED))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner transactions fees charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFileUpdate",
                                "batchTxn",
                                txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, 1000L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "File Delete Inner Batch Transaction with already deleted file - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> fileDeleteInnerBatchTransactionWithDeletedFileFailsOnHandle() {
                var contents = "0".repeat(1000).getBytes();
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyListNamed("WACL", List.of(PAYER)),
                        fileCreate("test")
                                .key("WACL")
                                .contents("original content")
                                .via("fileCreate"),
                        fileDelete("test")
                                .payingWith(PAYER)
                                .signedBy(PAYER, "WACL")
                                .via("fileDelete"),
                        atomicBatch(fileDelete("test")
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, "WACL")
                                        .via("innerTxnFileUpdate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(FILE_DELETED))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner transactions fees charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFileUpdate",
                                "batchTxn",
                                txnSize -> expectedFileDeleteFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, 1000L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "File Append Inner Batch Transaction with already deleted file - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> fileAppendInnerBatchTransactionWithDeletedFileFailsOnHandle() {
                var contents = "0".repeat(1000).getBytes();
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyListNamed("WACL", List.of(PAYER)),
                        fileCreate("test")
                                .key("WACL")
                                .contents("original content")
                                .via("fileCreate"),
                        fileDelete("test")
                                .payingWith(PAYER)
                                .signedBy(PAYER, "WACL")
                                .via("fileDelete"),
                        atomicBatch(fileAppend("test")
                                        .payingWith(PAYER)
                                        .content(contents)
                                        .signedBy(PAYER, "WACL")
                                        .via("innerTxnFileUpdate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(FILE_DELETED))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner transactions fees charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFileUpdate",
                                "batchTxn",
                                txnSize -> expectedFileAppendFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, 1000L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @LeakyHapiTest(
                    requirement = {PROPERTY_OVERRIDES},
                    overrides = {"files.maxSizeKb"})
            @DisplayName(
                    "FileCreate Inner Batch Transaction with oversized content - Fails on Handle - Outer fee charged")
            final Stream<DynamicTest> fileCreateInnerBatchTransactionWithOversizedContentFailsOnHandle() {
                final int maxSizeKb = 3;
                final int oversizedBytes = maxSizeKb * 1024 + 1; // 3073
                final byte[] oversizedContent = new byte[oversizedBytes];
                Arrays.fill(oversizedContent, (byte) 'a');

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        overriding("files.maxSizeKb", String.valueOf(maxSizeKb)),
                        newKeyListNamed("WACL", List.of(PAYER)),
                        atomicBatch(fileCreate("oversized")
                                        .key("WACL")
                                        .contents(oversizedContent)
                                        .payingWith(PAYER)
                                        .via("innerTxnFileCreate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(MAX_FILE_SIZE_EXCEEDED))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner txn fee charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFileCreate",
                                "batchTxn",
                                txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        STATE_BYTES, (long) oversizedBytes,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }
        }

        @Nested
        @DisplayName("Crypto Service Atomic Batch Inner Failures")
        class CryptoServiceAtomicBatchInnerFailures {
            @HapiTest
            @DisplayName("Crypto Transfer Inner Batch Transaction with Insufficient Sender Balance Fails on Handle")
            final Stream<DynamicTest> batchTransactionWithInsufficientSenderBalanceFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        atomicBatch(cryptoTransfer(
                                                moving(101L, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, OWNER)
                                        .via("innerTxn")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INSUFFICIENT_TOKEN_BALANCE))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner transactions fees charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxn",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 2L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName("Crypto Delete Inner Batch Transaction with non-existent account Fails on Handle")
            final Stream<DynamicTest> batchTransactionCryptoDeleteWithNonExistentAccountFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        atomicBatch(cryptoDelete("0.0.999999")
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxn")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_ACCOUNT_ID))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // validate inner transactions fees charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxn",
                                "batchTxn",
                                txnSize -> expectedCryptoDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "CryptoCreate Inner Batch Transaction with invalid alias key - Fails on Precheck - No fees charged")
            final Stream<DynamicTest> cryptoCreateInnerBatchTransactionWithInvalidAliasKeyFailsOnPrecheck() {
                final String ALIAS_KEY = "aliasKey";
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyNamed(ALIAS_KEY).shape(KeyShape.ED25519),
                        withOpContext((spec, opLog) -> {
                            var ed25519Key = spec.registry().getKey(ALIAS_KEY);
                            allRunFor(
                                    spec,
                                    atomicBatch(cryptoCreate("newAccount")
                                                    .key(ALIAS_KEY)
                                                    .alias(ed25519Key.getEd25519())
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER, ALIAS_KEY)
                                                    .via("innerTxnCryptoCreate")
                                                    .batchKey(BATCH_OPERATOR)
                                                    .hasPrecheck(INVALID_ALIAS_KEY))
                                            .payingWith(PAYER)
                                            .signedBy(PAYER, BATCH_OPERATOR)
                                            .via("batchTxn")
                                            .hasPrecheck(INVALID_ALIAS_KEY),

                                    // no txn record created — precheck failure
                                    getTxnRecord("batchTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                                    getTxnRecord("innerTxnCryptoCreate").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
                        })));
            }

            @HapiTest
            @DisplayName(
                    "CryptoUpdate Inner Batch Transaction with invalid new key - Fails on Precheck - No fees charged")
            final Stream<DynamicTest> cryptoUpdateInnerBatchTransactionWithInvalidNewKeyFailsOnPrecheck() {
                // create invalid key
                final SigControl emptyKey = threshOf(0, 0);

                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyNamed("invalidKey").shape(emptyKey),
                        atomicBatch(cryptoUpdate(PAYER)
                                        .key("invalidKey")
                                        .payingWith(PAYER)
                                        .via("innerTxnCryptoUpdate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_ADMIN_KEY))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn fee charged (payer charged even on handle failure)
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnCryptoUpdate",
                                "batchTxn",
                                txnSize -> expectedCryptoUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }
        }

        @Nested
        @DisplayName("Consensus Service Inner Transaction Failures")
        class ConsensusServiceAtomicBatchInnerFailures {
            @HapiTest
            @DisplayName(
                    "Topic Submit Message Inner Batch Transaction to deleted topic - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> topicSubmitMessageToDeletedTopicFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        newKeyNamed(adminKey),
                        newKeyNamed(submitKey),
                        createTopic("topic").adminKeyName(adminKey).submitKeyName(submitKey),
                        deleteTopic("topic").payingWith(PAYER).signedBy(PAYER, adminKey),
                        atomicBatch(submitMessageTo("topic")
                                        .message("test message")
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxnSubmit")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_TOPIC_ID))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnSubmit",
                                "batchTxn",
                                txnSize -> expectedTopicSubmitMessageFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "Topic Update Inner Batch Transaction to non-existent topic - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> topicUpdateNonExistentTopicFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        atomicBatch(updateTopic("0.0.999999")
                                        .memo("new memo")
                                        .payingWith(PAYER)
                                        .via("innerTxnUpdate")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_TOPIC_ID))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnUpdate",
                                "batchTxn",
                                txnSize -> expectedTopicUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "Topic Delete Inner Batch Transaction to non-existent topic - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> topicDeleteNonExistentTopicFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        atomicBatch(deleteTopic("0.0.999999")
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxnDelete")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_TOPIC_ID))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnDelete",
                                "batchTxn",
                                txnSize -> expectedTopicDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }
        }

        @Nested
        @DisplayName("Token Service Inner Transaction Failures")
        class TokenServiceAtomicBatchInnerFailures {
            @HapiTest
            @DisplayName("TokenMint Inner Batch Transaction exceeding max supply - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> tokenMintExceedingMaxSupplyFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .supplyType(TokenSupplyType.FINITE)
                                .maxSupply(100)
                                .initialSupply(100)
                                .treasury(OWNER)
                                .adminKey(adminKey)
                                .supplyKey(supplyKey),
                        atomicBatch(mintToken(FUNGIBLE_TOKEN, 1)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, supplyKey)
                                        .via("innerTxnMint")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(TOKEN_MAX_SUPPLY_REACHED))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn fee charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnMint",
                                "batchTxn",
                                txnSize -> expectedTokenMintFungibleFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "TokenTransfer Inner Batch Transaction with frozen account - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> tokenTransferFrozenAccountFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000)
                                .treasury(OWNER)
                                .adminKey(adminKey)
                                .freezeKey(freezeKey),
                        tokenAssociate(PAYER, FUNGIBLE_TOKEN),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(OWNER, PAYER)),
                        tokenFreeze(FUNGIBLE_TOKEN, PAYER),
                        atomicBatch(cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(PAYER, RECEIVER_ASSOCIATED_FIRST))
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxnTransfer")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn fee charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnTransfer",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "TokenTransfer Inner Batch Transaction with paused token - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> tokenTransferPausedTokenFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000)
                                .treasury(OWNER)
                                .adminKey(adminKey)
                                .pauseKey(pauseKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        tokenPause(FUNGIBLE_TOKEN),
                        atomicBatch(cryptoTransfer(moving(10, FUNGIBLE_TOKEN).between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, OWNER)
                                        .via("innerTxnTransfer")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(TOKEN_IS_PAUSED))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn fee charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnTransfer",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "TokenBurn Inner Batch Transaction exceeding treasury balance - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> tokenBurnExceedingBalanceFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(100)
                                .treasury(OWNER)
                                .adminKey(adminKey)
                                .supplyKey(supplyKey),
                        atomicBatch(burnToken(FUNGIBLE_TOKEN, 101)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER, supplyKey)
                                        .via("innerTxnBurn")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_TOKEN_BURN_AMOUNT))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn fee charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnBurn",
                                "batchTxn",
                                txnSize -> expectedTokenBurnFungibleFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1)));
            }

            @HapiTest
            @DisplayName(
                    "TokenAirdrop Inner Batch Transaction sender not associated to token - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> tokenAirdropSenderNotAssociatedFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 1000L, OWNER, adminKey),
                        // PAYER is not associated with FUNGIBLE_TOKEN
                        atomicBatch(tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(PAYER, RECEIVER_NOT_ASSOCIATED))
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxnAirdrop")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn fee charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnAirdrop",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1),
                        getAccountInfo(RECEIVER_NOT_ASSOCIATED).hasNoTokenRelationship(FUNGIBLE_TOKEN)));
            }

            @HapiTest
            @DisplayName(
                    "TokenAirdrop Inner Batch Transaction sender frozen for token - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> tokenAirdropSenderFrozenFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        tokenCreate(FUNGIBLE_TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000)
                                .treasury(OWNER)
                                .adminKey(adminKey)
                                .freezeKey(freezeKey),
                        tokenAssociate(PAYER, FUNGIBLE_TOKEN),
                        cryptoTransfer(moving(100, FUNGIBLE_TOKEN).between(OWNER, PAYER)),
                        tokenFreeze(FUNGIBLE_TOKEN, PAYER),
                        atomicBatch(tokenAirdrop(moving(1, FUNGIBLE_TOKEN).between(PAYER, RECEIVER_NOT_ASSOCIATED))
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxnAirdrop")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(ACCOUNT_FROZEN_FOR_TOKEN))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn fee charged
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnAirdrop",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ACCOUNTS, 2L,
                                        TOKEN_TYPES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                1),
                        getAccountBalance(PAYER).hasTokenBalance(FUNGIBLE_TOKEN, 100L),
                        getAccountInfo(RECEIVER_NOT_ASSOCIATED).hasNoTokenRelationship(FUNGIBLE_TOKEN)));
            }
        }

        @Nested
        @DisplayName("Smart Contract Inner Transaction Failures")
        class SmartContractAtomicBatchInnerFailures {
            @HapiTest
            @DisplayName("ContractCall Inner Batch Transaction with insufficient gas - Fails on Pre-Handle")
            final Stream<DynamicTest> contractCallInsufficientGasFailsOnPreHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).gas(200_000L),
                        atomicBatch(contractCall(CONTRACT, "contractCall1Byte", (Object) new byte[] {0})
                                        .gas(1L)
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxnContractCall")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasPrecheck(INSUFFICIENT_GAS))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasPrecheck(INSUFFICIENT_GAS),

                        // assert no txn record is created
                        getTxnRecord("batchTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getTxnRecord("innerTxnContractCall").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
            }

            @HapiTest
            @DisplayName(
                    "EthereumTransaction Inner Batch Transaction with invalid nonce - Fails on Handle - Full fees charged")
            final Stream<DynamicTest> ethereumTransactionWrongNonceFailsOnHandle() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        cryptoCreate(RELAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).gas(200_000L),
                        newKeyNamed(SECP_256K1_SOURCE_KEY).shape(SECP_256K1_SHAPE),
                        cryptoTransfer(tinyBarsFromAccountToAlias(GENESIS, SECP_256K1_SOURCE_KEY, ONE_HUNDRED_HBARS)),
                        atomicBatch(ethereumCall(CONTRACT, "contractCall1Byte", (Object) new byte[] {0})
                                        .type(EthTxData.EthTransactionType.EIP1559)
                                        .signingWith(SECP_256K1_SOURCE_KEY)
                                        .payingWith(RELAYER)
                                        .nonce(5) // account has nonce 0
                                        .gasLimit(100_000L)
                                        .via("innerTxnEthCall")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(WRONG_NONCE))
                                .payingWith(PAYER)
                                .signedBy(PAYER, BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),

                        // validate outer batch fee charged (paid by PAYER)
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                1),
                        // validate inner txn payer (RELAYER) charged — WRONG_NONCE fails before EVM execution
                        validateInnerTxnChargedUsd("innerTxnEthCall", "batchTxn", 0.0001, 10)));
            }
        }
    }

    private HapiTokenCreate createFungibleTokenWithoutCustomFees(
            String tokenName, long supply, String treasury, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(supply)
                .treasury(treasury)
                .adminKey(adminKey)
                .tokenType(FUNGIBLE_COMMON);
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(PAYER_INSUFFICIENT_BALANCE).balance(ONE_HBAR / 100000),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_NOT_ASSOCIATED).balance(ONE_HBAR),
                newKeyNamed(adminKey),
                newKeyNamed(submitKey),
                newKeyNamed(freezeKey),
                newKeyNamed(pauseKey),
                newKeyNamed(supplyKey));
    }
}
