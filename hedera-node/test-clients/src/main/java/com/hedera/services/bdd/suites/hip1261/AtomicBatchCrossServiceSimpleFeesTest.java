// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.includingFungiblePendingAirdrop;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAirdrop;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingWithAllowance;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.flattened;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedAtomicBatchFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedContractCreateSimpleFeesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoApproveAllowanceFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTAndNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoTransferNFTFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedFileUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenAirdropSurchargeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenAssociateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenMintNftFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicSubmitMessageFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTopicUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.getChargedGasForContractCreateInnerTxn;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateInnerChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_BASE_FEE_USD;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.TOKEN_ASSOCIATE_EXTRA_FEE_USD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.ACCOUNTS;
import static org.hiero.hapi.support.fees.Extra.AIRDROPS;
import static org.hiero.hapi.support.fees.Extra.ALLOWANCES;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.hiero.hapi.support.fees.Extra.STATE_BYTES;
import static org.hiero.hapi.support.fees.Extra.TOKEN_ASSOCIATE;
import static org.hiero.hapi.support.fees.Extra.TOKEN_MINT_NFT;
import static org.hiero.hapi.support.fees.Extra.TOKEN_TYPES;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenCreate;
import com.hedera.services.bdd.spec.transactions.token.HapiTokenMint;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class AtomicBatchCrossServiceSimpleFeesTest {
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final String PAYER = "payer";
    private static final String OWNER = "owner";
    private static final String RECEIVER_ASSOCIATED_FIRST = "receiverAssociatedFirst";
    private static final String RECEIVER_ASSOCIATED_SECOND = "receiverAssociatedSecond";
    private static final String adminKey = "adminKey";
    private static final String submitKey = "submitKey";
    private static final String supplyKey = "supplyKey";
    private static final String FUNGIBLE_TOKEN = "fungibleToken";
    private static final String NON_FUNGIBLE_TOKEN = "nonFungibleToken";
    private static final String EMPTY_CONSTRUCTOR_CONTRACT = "EmptyConstructor";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("Atomic Batch Cross Service Simple Fees Tests")
    class AtomicBatchCrossServiceSimpleFeesTests {
        @Nested
        @DisplayName("Atomic Batch Simple Fees - Payer Relationship Test Cases")
        class AtomicBatchSimpleFeesPayerRelationshipTestCases {
            @HapiTest
            @DisplayName("Atomic Batch with the same inner and outer payer - Full Fees Charged")
            Stream<DynamicTest> atomicBatchWithSameInnerAndOuterTxnPayerFullFeesCharged() {
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
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", PAYER),
                        validateChargedAccount("innerTxn", PAYER),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxn",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName("Atomic Batch with different inner and outer payer - Full Fees Charged")
            Stream<DynamicTest> atomicBatchWithDifferentInnerAndOuterTxnPayerFullFeesCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        atomicBatch(cryptoCreate("test")
                                        .payingWith(PAYER)
                                        .signedBy(PAYER)
                                        .via("innerTxn")
                                        .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxn", PAYER),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxn",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch - Multiple inner transactions with the same inner and outer payer - Full Fees Charged")
            Stream<DynamicTest> atomicBatchMultipleInnerTxnWithSameInnerAndOuterTxnPayerFullFeesCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        atomicBatch(
                                        cryptoCreate("test")
                                                .payingWith(BATCH_OPERATOR)
                                                .signedBy(BATCH_OPERATOR)
                                                .via("innerTxnFirst")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(BATCH_OPERATOR)
                                                .signedBy(OWNER, BATCH_OPERATOR)
                                                .via("innerTxnSecond")
                                                .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnFirst", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnSecond", BATCH_OPERATOR),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFirst",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnSecond",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 1L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch - Multiple inner transactions with the same inner and different outer payer - Full Fees Charged")
            Stream<DynamicTest> atomicBatchMultipleInnerTxnWithSameInnerAndDifferentOuterTxnPayerFullFeesCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        atomicBatch(
                                        cryptoCreate("test")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER)
                                                .via("innerTxnFirst")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(PAYER)
                                                .signedBy(OWNER, PAYER)
                                                .via("innerTxnSecond")
                                                .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnFirst", PAYER),
                        validateChargedAccount("innerTxnSecond", PAYER),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFirst",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnSecond",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 1L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch - Multiple inner transactions with different inner payers and separate outer payer - Full Fees Charged")
            Stream<DynamicTest> atomicBatchMultipleInnerTxnWithDifferentInnerAndSeparateOuterTxnPayerFullFeesCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                        atomicBatch(
                                        cryptoCreate("test")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER)
                                                .via("innerTxnFirst")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(OWNER)
                                                .signedBy(OWNER)
                                                .via("innerTxnSecond")
                                                .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnFirst", PAYER),
                        validateChargedAccount("innerTxnSecond", OWNER),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFirst",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnSecond",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, ACCOUNTS, 2L, TOKEN_TYPES, 1L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch - Three inner transactions with three different inner payers and separate outer payer - Full Fees Charged")
            Stream<DynamicTest>
                    atomicBatchThreeInnerTxnWithThreeDifferentInnerPayerAndSeparateOuterTxnPayerFullFeesCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 0, 5),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        atomicBatch(
                                        cryptoCreate("test")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER)
                                                .via("innerTxnFirst")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(OWNER)
                                                .signedBy(OWNER)
                                                .via("innerTxnSecond")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                                .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                                                .via("innerTxnThird")
                                                .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnFirst", PAYER),
                        validateChargedAccount("innerTxnSecond", OWNER),
                        validateChargedAccount("innerTxnThird", RECEIVER_ASSOCIATED_FIRST),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFirst",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnSecond",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, ACCOUNTS, 2L, TOKEN_TYPES, 1L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnThird",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferNFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 3L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch - Three inner transactions with two different inner payers and separate outer payer - Full Fees Charged")
            Stream<DynamicTest>
                    atomicBatchThreeInnerTxnWithTwoDifferentInnerPayersAndSeparateOuterTxnPayerFullFeesCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 0, 5),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        atomicBatch(
                                        cryptoCreate("test")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER)
                                                .via("innerTxnFirst")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(PAYER)
                                                .signedBy(OWNER, PAYER)
                                                .via("innerTxnSecond")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                                .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                                                .via("innerTxnThird")
                                                .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnFirst", PAYER),
                        validateChargedAccount("innerTxnSecond", PAYER),
                        validateChargedAccount("innerTxnThird", RECEIVER_ASSOCIATED_FIRST),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFirst",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnSecond",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 1L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnThird",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferNFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 3L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch - Three inner transactions with batch operator as one inner payer and outer payer - Full Fees Charged")
            Stream<DynamicTest>
                    atomicBatchThreeInnerTxnWithBatchOperatorAsInnerPayerDifferentInnerAndOuterTxnPayerFullFeesCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 0, 5),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        atomicBatch(
                                        cryptoCreate("test")
                                                .payingWith(PAYER)
                                                .signedBy(PAYER)
                                                .via("innerTxnFirst")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(BATCH_OPERATOR)
                                                .signedBy(OWNER, BATCH_OPERATOR)
                                                .via("innerTxnSecond")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                                .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                                                .via("innerTxnThird")
                                                .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn"),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnFirst", PAYER),
                        validateChargedAccount("innerTxnSecond", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnThird", RECEIVER_ASSOCIATED_FIRST),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFirst",
                                "batchTxn",
                                txnSize -> expectedCryptoCreateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnSecond",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 1L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnThird",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferNFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 3L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch with one inner txn - batch operator is inner and outer payer - inner transaction fails on handle - Batch operator is charged")
            Stream<DynamicTest>
                    atomicBatchWithOneInnerTxnAndSameInnerAndOuterPayerFailsOnHandlePayerChargedInnerAndOuterFees() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 0, 5),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        atomicBatch(cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 8L)
                                                .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                        .payingWith(BATCH_OPERATOR)
                                        .signedBy(OWNER, BATCH_OPERATOR)
                                        .via("innerTxn")
                                        .batchKey(BATCH_OPERATOR)
                                        .hasKnownStatus(INVALID_NFT_ID))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxn", BATCH_OPERATOR),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxn",
                                "batchTxn",
                                txnSize -> expectedCryptoTransferNFTFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 1L, PROCESSING_BYTES, (long)
                                                txnSize)),
                                0.1)));
            }

            @HapiTest
            @DisplayName(
                    "Atomic Batch - Three inner transactions - First txn fails on handle - Subsequent transactions are not charged")
            Stream<DynamicTest> atomicBatchThreeInnerTxnFirstInnerTxnFailsOnHandleSubsequentTxnAreNotCharged() {
                return hapiTest(flattened(
                        createAccountsAndKeys(),
                        createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                        createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                        mintNFT(NON_FUNGIBLE_TOKEN, 0, 5),
                        tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                        atomicBatch(
                                        updateTopic("0.0.999999")
                                                .memo("new memo")
                                                .payingWith(PAYER)
                                                .via("innerTxnFirst")
                                                .batchKey(BATCH_OPERATOR)
                                                .hasKnownStatus(INVALID_TOPIC_ID),
                                        cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(PAYER)
                                                .signedBy(OWNER, PAYER)
                                                .via("innerTxnSecond")
                                                .batchKey(BATCH_OPERATOR),
                                        cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L)
                                                        .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                .payingWith(PAYER)
                                                .signedBy(OWNER, PAYER)
                                                .via("innerTxnThird")
                                                .batchKey(BATCH_OPERATOR))
                                .via("batchTxn")
                                .payingWith(BATCH_OPERATOR)
                                .signedBy(BATCH_OPERATOR)
                                .via("batchTxn")
                                .hasKnownStatus(INNER_TRANSACTION_FAILED),
                        validateChargedAccount("batchTxn", BATCH_OPERATOR),
                        validateChargedAccount("innerTxnFirst", PAYER),
                        validateChargedUsdWithinWithTxnSize(
                                "batchTxn",
                                txnSize -> expectedAtomicBatchFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateInnerChargedUsdWithinWithTxnSize(
                                "innerTxnFirst",
                                "batchTxn",
                                txnSize -> expectedTopicUpdateFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        // assert no txn records are created for unreached inner txns
                        getTxnRecord("innerTxnSecond").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getTxnRecord("innerTxnThird").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND)));
            }
        }

        @Nested
        @DisplayName("Service-Level Cross-Service Atomic Batch Test Cases")
        class ServiceLevelCrossServiceAtomicBatchTestCases {
            @Nested
            @DisplayName("Token Lifecycle Scenarios")
            class TokenLifecycleScenarios {
                @HapiTest
                @DisplayName(
                        "Atomic Batch - Token associate and token mint with the same inner and separate outer payers - Full Fees Charged")
                Stream<DynamicTest>
                        atomicBatchTokenAssociateAndMintWithSameInnerAndSeparateOuterPayersFullFeesCharged() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            atomicBatch(
                                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN)
                                                    .payingWith(PAYER)
                                                    .signedBy(RECEIVER_ASSOCIATED_FIRST, PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            mintToken(
                                                            NON_FUNGIBLE_TOKEN,
                                                            IntStream.range(0, 5)
                                                                    .mapToObj(a ->
                                                                            ByteString.copyFromUtf8(String.valueOf(a)))
                                                                    .toList())
                                                    .payingWith(PAYER)
                                                    .signedBy(OWNER, PAYER, supplyKey)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .via("batchTxn")
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                            SIGNATURES, 2L, TOKEN_ASSOCIATE, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                            SIGNATURES, 3L, TOKEN_MINT_NFT, 5L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - Token associate and token transfer with the same inner and outer payer - Full Fees Charged")
                Stream<DynamicTest>
                        atomicBatchTokenAssociateAndTokenTransferWithSameInnerAndOuterPayerFullFeesCharged() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 0, 5),
                            atomicBatch(
                                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, NON_FUNGIBLE_TOKEN)
                                                    .payingWith(PAYER)
                                                    .signedBy(RECEIVER_ASSOCIATED_FIRST, PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            cryptoTransfer(movingUnique(NON_FUNGIBLE_TOKEN, 1L, 2L, 3L)
                                                            .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                    .payingWith(PAYER)
                                                    .signedBy(OWNER, PAYER)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .via("batchTxn")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", PAYER),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedTokenAssociateFullFeeUsd(Map.of(
                                            SIGNATURES, 2L, TOKEN_ASSOCIATE, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L, ACCOUNTS, 2L, TOKEN_TYPES, 3L, PROCESSING_BYTES, (long)
                                                    txnSize)),
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - Token airdrop inner transactions with different payers and separate outer payer - Full Fees Charged")
                Stream<DynamicTest>
                        atomicBatchTokenAirdropInnerTxnWithDifferentPayersAndSeparateOuterPayerFullFeesCharged() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            mintNFT(NON_FUNGIBLE_TOKEN, 0, 5),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            tokenAssociate(RECEIVER_ASSOCIATED_SECOND, FUNGIBLE_TOKEN, NON_FUNGIBLE_TOKEN),
                            atomicBatch(
                                            tokenAirdrop(
                                                            moving(10L, FUNGIBLE_TOKEN)
                                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST),
                                                            movingUnique(NON_FUNGIBLE_TOKEN, 1L)
                                                                    .between(OWNER, RECEIVER_ASSOCIATED_SECOND))
                                                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                                    .signedBy(OWNER, RECEIVER_ASSOCIATED_FIRST)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            tokenAirdrop(
                                                            moving(10L, FUNGIBLE_TOKEN)
                                                                    .between(OWNER, RECEIVER_ASSOCIATED_SECOND),
                                                            movingUnique(NON_FUNGIBLE_TOKEN, 2L, 3L, 4L)
                                                                    .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                                                    .signedBy(OWNER, RECEIVER_ASSOCIATED_SECOND)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .via("batchTxn")
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", PAYER),
                            validateChargedAccount("innerTxnFirst", RECEIVER_ASSOCIATED_FIRST),
                            validateChargedAccount("innerTxnSecond", RECEIVER_ASSOCIATED_SECOND),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L, ACCOUNTS, 3L, TOKEN_TYPES, 2L, PROCESSING_BYTES, (long)
                                                    txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTAndNFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L, ACCOUNTS, 3L, TOKEN_TYPES, 4L, PROCESSING_BYTES, (long)
                                                    txnSize)),
                                    0.1)));
                }
            }

            @Nested
            @DisplayName("File Lifecycle Scenarios")
            class FileLifecycleScenarios {
                @HapiTest
                @DisplayName(
                        "File update transactions with the same inner payer and txn sizes and separate outer payer - Full fees charged")
                final Stream<DynamicTest>
                        fileUpdateInnerTransactionsWithSameInnerPayerAndTxnSizesAndSeparateOuterPayerFullFeesCharged() {
                    final var updateContentsFirst = "1".repeat(800).getBytes();
                    final var updateContentsSecond = "2".repeat(800).getBytes();

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyListNamed("WACL", List.of(PAYER)),
                            fileCreate("testFile")
                                    .key("WACL")
                                    .contents("0".repeat(800))
                                    .via("innerTxnFileCreate"),
                            atomicBatch(
                                            fileUpdate("testFile")
                                                    .contents(updateContentsFirst)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            fileUpdate("testFile")
                                                    .contents(updateContentsSecond)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            STATE_BYTES, 800L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            STATE_BYTES, 800L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1)));
                }

                @HapiTest
                @DisplayName(
                        "File update and File delete transactions with the same inner payer and separate outer payer - Full fees charged")
                final Stream<DynamicTest>
                        fileUpdateAndFileDeleteInnerTransactionsWithSameInnerPayerAndSeparateOuterPayerFullFeesCharged() {
                    final var updateContentsFirst = "1".repeat(1000).getBytes();

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyListNamed("WACL", List.of(PAYER)),
                            fileCreate("testFile")
                                    .key("WACL")
                                    .contents("0".repeat(800))
                                    .via("fileCreateTxn"),
                            atomicBatch(
                                            fileUpdate("testFile")
                                                    .contents(updateContentsFirst)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            fileDelete("testFile")
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            STATE_BYTES, 800L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedFileDeleteFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    1)));
                }

                @HapiTest
                @DisplayName(
                        "File update transactions with different inner payers and txn sizes and separate outer payer - Full fees charged")
                final Stream<DynamicTest>
                        fileUpdateInnerTransactionsWithDifferentInnerPayerAndTxnSizesAndSeparateOuterPayerFullFeesCharged() {
                    final var updateContentsFirst = "1".repeat(800).getBytes();
                    final var updateContentsSecond = "2".repeat(1000).getBytes();
                    final var updateContentsThird = "2".repeat(1024).getBytes();

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyListNamed("WACL", List.of(PAYER)),
                            fileCreate("testFile")
                                    .key("WACL")
                                    .contents("0".repeat(800))
                                    .via("innerTxnFileCreate"),
                            atomicBatch(
                                            fileUpdate("testFile")
                                                    .contents(updateContentsFirst)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            fileUpdate("testFile")
                                                    .contents(updateContentsSecond)
                                                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                                    .signedBy(RECEIVER_ASSOCIATED_FIRST, "WACL")
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR),
                                            fileUpdate("testFile")
                                                    .contents(updateContentsThird)
                                                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                                                    .signedBy(RECEIVER_ASSOCIATED_SECOND, "WACL")
                                                    .via("innerTxnThird")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", RECEIVER_ASSOCIATED_FIRST),
                            validateChargedAccount("innerTxnThird", RECEIVER_ASSOCIATED_SECOND),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            STATE_BYTES, 800L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            STATE_BYTES, 1000L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnThird",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            STATE_BYTES, 1024L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1)));
                }

                @HapiTest
                @DisplayName(
                        "File create transactions with different inner payers and txn sizes and separate outer payer - Full fees charged")
                final Stream<DynamicTest>
                        fileCreateInnerTransactionsWithDifferentInnerPayerAndTxnSizesAndSeparateOuterPayerFullFeesCharged() {
                    final var createContentsFirst = "1".repeat(800).getBytes();
                    final var createContentsSecond = "2".repeat(1000).getBytes();
                    final var createContentsThird = "2".repeat(1024).getBytes();

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyListNamed("WACL", List.of(PAYER)),
                            atomicBatch(
                                            fileCreate("testFileFirst")
                                                    .key("WACL")
                                                    .contents(createContentsFirst)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            fileCreate("testFileSecond")
                                                    .key("WACL")
                                                    .contents(createContentsSecond)
                                                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                                    .signedBy(RECEIVER_ASSOCIATED_FIRST, "WACL")
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR),
                                            fileCreate("testFileThird")
                                                    .key("WACL")
                                                    .contents(createContentsThird)
                                                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                                                    .signedBy(RECEIVER_ASSOCIATED_SECOND, "WACL")
                                                    .via("innerTxnThird")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", RECEIVER_ASSOCIATED_FIRST),
                            validateChargedAccount("innerTxnThird", RECEIVER_ASSOCIATED_SECOND),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            KEYS, 1L,
                                            STATE_BYTES, 800L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            KEYS, 1L,
                                            STATE_BYTES, 1000L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnThird",
                                    "batchTxn",
                                    txnSize -> expectedFileUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            KEYS, 1L,
                                            STATE_BYTES, 1024L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    1)));
                }
            }

            @Nested
            @DisplayName("Account And Transfer Lifecycle Scenarios")
            class AccountAndTransferLifecycleScenarios {
                @HapiTest
                @DisplayName(
                        "Crypto update and Crypto Transfer transactions with the same inner payer and separate outer payer - Full fees charged")
                final Stream<DynamicTest>
                        cryptoUpdateAndCryptoTransferInnerTransactionsWithSameInnerPayerAndSeparateOuterPayerFullFeesCharged() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("test").key(adminKey).balance(ONE_HBAR).via("cryptoCreateTxn"),
                            tokenAssociate("test", FUNGIBLE_TOKEN),
                            atomicBatch(
                                            cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                            .between(OWNER, "test"))
                                                    .payingWith(PAYER)
                                                    .signedBy(OWNER, PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            cryptoUpdate("test")
                                                    .key(supplyKey)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER, adminKey, supplyKey)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR),
                                            cryptoTransfer(moving(1L, FUNGIBLE_TOKEN)
                                                            .between("test", OWNER))
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER, supplyKey)
                                                    .via("innerTxnThird")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", PAYER),
                            validateChargedAccount("innerTxnThird", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 3L,
                                            KEYS, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnThird",
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
                        "Crypto update and Crypto Transfer transactions with the same inner and outer payer - the batch operator - Full fees charged")
                final Stream<DynamicTest>
                        cryptoUpdateAndCryptoTransferInnerTransactionsWithSameInnerAndOuterPayerTheBatchOperatorFullFeesCharged() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("test").key(adminKey).balance(ONE_HBAR).via("cryptoCreateTxn"),
                            tokenAssociate("test", FUNGIBLE_TOKEN),
                            atomicBatch(
                                            cryptoTransfer(moving(10L, FUNGIBLE_TOKEN)
                                                            .between(OWNER, "test"))
                                                    .payingWith(BATCH_OPERATOR)
                                                    .signedBy(OWNER, BATCH_OPERATOR)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            cryptoUpdate("test")
                                                    .key(supplyKey)
                                                    .payingWith(BATCH_OPERATOR)
                                                    .signedBy(BATCH_OPERATOR, adminKey, supplyKey)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR),
                                            cryptoTransfer(moving(1L, FUNGIBLE_TOKEN)
                                                            .between("test", OWNER))
                                                    .payingWith(BATCH_OPERATOR)
                                                    .signedBy(BATCH_OPERATOR, supplyKey)
                                                    .via("innerTxnThird")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnSecond", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnThird", BATCH_OPERATOR),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 3L,
                                            KEYS, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnThird",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1)));
                }
            }

            @Nested
            @DisplayName("Topic Lifecycle Scenarios")
            class TopicLifecycleScenarios {

                @HapiTest
                @DisplayName(
                        "Atomic Batch - Topic update and submit message with the same inner payer and separate outer payer - Full fees charged")
                final Stream<DynamicTest>
                        topicUpdateAndSubmitMessageWithSameInnerPayerAndSeparateOuterPayerFullFeesCharged() {
                    final var message = "Hello Hedera".getBytes();

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createTopic("testTopic").adminKeyName(adminKey).submitKeyName(submitKey),
                            atomicBatch(
                                            updateTopic("testTopic")
                                                    .memo("updated memo")
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER, adminKey)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            submitMessageTo("testTopic")
                                                    .message(message)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER, submitKey)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedTopicUpdateFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                            SIGNATURES, 2L, STATE_BYTES, (long) message.length, PROCESSING_BYTES, (long)
                                                    txnSize)),
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - Topic update and submit message with different inner payers and separate outer payer - Full fees charged")
                final Stream<DynamicTest>
                        topicUpdateAndSubmitMessageWithDifferentInnerPayersAndSeparateOuterPayerFullFeesCharged() {
                    final var message = "Hello Hedera".getBytes();

                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createTopic("testTopic").adminKeyName(adminKey).submitKeyName(submitKey),
                            atomicBatch(
                                            updateTopic("testTopic")
                                                    .memo("updated memo")
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER, adminKey)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            submitMessageTo("testTopic")
                                                    .message(message)
                                                    .payingWith(OWNER)
                                                    .signedBy(OWNER, submitKey)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", OWNER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedTopicUpdateFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedTopicSubmitMessageFullFeeUsd(Map.of(
                                            SIGNATURES, 2L, STATE_BYTES, (long) message.length, PROCESSING_BYTES, (long)
                                                    txnSize)),
                                    0.1)));
                }
            }

            @Nested
            @DisplayName("Token Airdrop Scenarios")
            class TokenAirdropScenarios {

                @HapiTest
                @DisplayName(
                        "Atomic Batch - Token airdrop inner txn with auto-association receiver (free slots) - Full fees charged")
                final Stream<DynamicTest> atomicBatchTokenAirdropWithAutoAssociationFullFeesCharged() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("receiverFreeAutoAssoc")
                                    .maxAutomaticTokenAssociations(5)
                                    .balance(ONE_HBAR),
                            atomicBatch(tokenAirdrop(
                                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, "receiverFreeAutoAssoc"))
                                            .payingWith(PAYER)
                                            .signedBy(OWNER, PAYER)
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                    SIGNATURES, 2L,
                                                    ACCOUNTS, 2L,
                                                    TOKEN_TYPES, 1L,
                                                    PROCESSING_BYTES, (long) txnSize))
                                            + TOKEN_ASSOCIATE_BASE_FEE_USD
                                            + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 1L)),
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - Token airdrop inner txn resulting in pending airdrop (no free slots) - fee differs from direct transfer to associated receiver")
                final Stream<DynamicTest> atomicBatchTokenAirdropResultingInPendingAirdropFeeChargedCorrectly() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("receiverNoAutoAssoc")
                                    .maxAutomaticTokenAssociations(0)
                                    .balance(ONE_HBAR),
                            atomicBatch(tokenAirdrop(moving(10L, FUNGIBLE_TOKEN).between(OWNER, "receiverNoAutoAssoc"))
                                            .payingWith(PAYER)
                                            .signedBy(OWNER, PAYER)
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            getTxnRecord("innerTxn")
                                    .hasPriority(recordWith()
                                            .pendingAirdrops(includingFungiblePendingAirdrop(
                                                    moving(10, FUNGIBLE_TOKEN).between(OWNER, "receiverNoAutoAssoc")))),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                    SIGNATURES, 2L,
                                                    ACCOUNTS, 2L,
                                                    TOKEN_TYPES, 1L,
                                                    PROCESSING_BYTES, (long) txnSize))
                                            + TOKEN_ASSOCIATE_BASE_FEE_USD
                                            + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 1L)),
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - Token airdrop inner txn to multiple receivers resulting in pending airdrops (no free slots) - Full fees charged correctly")
                final Stream<DynamicTest> atomicBatchTokenAirdropToMultipleReceiversFullFeesChargedCorrectly() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("receiverNoAutoAssocFirst")
                                    .maxAutomaticTokenAssociations(0)
                                    .balance(ONE_HBAR),
                            cryptoCreate("receiverNoAutoAssocSecond")
                                    .maxAutomaticTokenAssociations(0)
                                    .balance(ONE_HBAR),
                            atomicBatch(tokenAirdrop(
                                                    moving(10L, FUNGIBLE_TOKEN)
                                                            .between(OWNER, "receiverNoAutoAssocFirst"),
                                                    moving(10L, FUNGIBLE_TOKEN)
                                                            .between(OWNER, "receiverNoAutoAssocSecond"))
                                            .payingWith(PAYER)
                                            .signedBy(OWNER, PAYER)
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                    SIGNATURES, 2L,
                                                    ACCOUNTS, 3L,
                                                    TOKEN_TYPES, 1L,
                                                    PROCESSING_BYTES, (long) txnSize))
                                            + TOKEN_ASSOCIATE_BASE_FEE_USD * 2
                                            + expectedTokenAirdropSurchargeUsd(Map.of(AIRDROPS, 2L)),
                                    0.1)));
                }
            }

            @Nested
            @DisplayName("Crypto Update Scenarios")
            class CryptoUpdateScenarios {
                @HapiTest
                @DisplayName(
                        "Atomic Batch - CryptoUpdate inner txn with larger key list - FUll fees charged with extra fees charged to inner payer and batch payer charged outer fee only")
                final Stream<DynamicTest> cryptoUpdateChangingToKeyListExtrasFullFeesChargedCorrectly() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyNamed("newKey1"),
                            newKeyNamed("newKey2"),
                            newKeyNamed("newKey3"),
                            newKeyListNamed("largeKeyList", List.of("newKey1", "newKey2", "newKey3")),
                            cryptoCreate("test").key(adminKey).balance(ONE_HBAR),
                            atomicBatch(cryptoUpdate("test")
                                            .key("largeKeyList")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER, adminKey, "newKey1", "newKey2", "newKey3")
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 5L,
                                            KEYS, 3L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - CryptoUpdate inner txn with threshold key change - key complexity factor in inner fee isolated to inner payer only")
                final Stream<DynamicTest> cryptoUpdateChangingToThresholdKeyComplexityFactorChargedToInnerPayerOnly() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            newKeyNamed("threshKey").shape(threshOf(2, SIMPLE, SIMPLE)),
                            cryptoCreate("test").key(adminKey).balance(ONE_HBAR),
                            atomicBatch(cryptoUpdate("test")
                                            .key("threshKey")
                                            .payingWith(PAYER)
                                            .signedBy(PAYER, adminKey, "threshKey")
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoUpdateFullFeeUsd(Map.of(
                                            SIGNATURES, 4L,
                                            KEYS, 2L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1)));
                }
            }

            @Nested
            @DisplayName("Auto-Associations Scenarios")
            class AutoAssociationsScenarios {
                @HapiTest
                @DisplayName(
                        "Atomic Batch - CryptoTransfer inner txn triggering auto-association (receiver has free slots) - Full fees and auto-association fee charged correctly")
                final Stream<DynamicTest> cryptoTransferTriggeringAutoAssociationFullFeesChargedCorrectly() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("receiverFreeAutoAssoc")
                                    .maxAutomaticTokenAssociations(5)
                                    .balance(ONE_HBAR),
                            atomicBatch(cryptoTransfer(
                                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, "receiverFreeAutoAssoc"))
                                            .payingWith(PAYER)
                                            .signedBy(OWNER, PAYER)
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                    SIGNATURES, 2L,
                                                    ACCOUNTS, 2L,
                                                    TOKEN_TYPES, 1L,
                                                    PROCESSING_BYTES, (long) txnSize))
                                            + TOKEN_ASSOCIATE_EXTRA_FEE_USD,
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - CryptoTransfer inner txn triggering auto-association, inner payer equals batch payer - Full fees charged correctly to single account")
                final Stream<DynamicTest>
                        cryptoTransferTriggeringAutoAssociationInnerPayerEqualsBatchPayerFullFeesChargedCorrectlyToSingleAccount() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("receiverFreeAutoAssoc")
                                    .maxAutomaticTokenAssociations(5)
                                    .balance(ONE_HBAR),
                            atomicBatch(cryptoTransfer(
                                                    moving(10L, FUNGIBLE_TOKEN).between(OWNER, "receiverFreeAutoAssoc"))
                                            .payingWith(BATCH_OPERATOR)
                                            .signedBy(OWNER, BATCH_OPERATOR)
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", BATCH_OPERATOR),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                    SIGNATURES, 2L,
                                                    ACCOUNTS, 2L,
                                                    TOKEN_TYPES, 1L,
                                                    PROCESSING_BYTES, (long) txnSize))
                                            + TOKEN_ASSOCIATE_EXTRA_FEE_USD,
                                    0.1)));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - CryptoTransfer inner txn with multiple auto-associations - Full fees charged correctly with associations")
                final Stream<DynamicTest>
                        cryptoTransferMultipleAutoAssociationsFullFeesChargedCorrectlyWithAssociationCount() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            cryptoCreate("receiverFreeAutoAssocFirst")
                                    .maxAutomaticTokenAssociations(5)
                                    .balance(ONE_HBAR),
                            cryptoCreate("receiverFreeAutoAssocSecond")
                                    .maxAutomaticTokenAssociations(5)
                                    .balance(ONE_HBAR),
                            atomicBatch(cryptoTransfer(
                                                    moving(10L, FUNGIBLE_TOKEN)
                                                            .between(OWNER, "receiverFreeAutoAssocFirst"),
                                                    moving(10L, FUNGIBLE_TOKEN)
                                                            .between(OWNER, "receiverFreeAutoAssocSecond"))
                                            .payingWith(PAYER)
                                            .signedBy(OWNER, PAYER)
                                            .via("innerTxn")
                                            .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxn", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxn",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                                    SIGNATURES, 2L,
                                                    ACCOUNTS, 3L,
                                                    TOKEN_TYPES, 1L,
                                                    PROCESSING_BYTES, (long) txnSize))
                                            + TOKEN_ASSOCIATE_EXTRA_FEE_USD * 2,
                                    0.1)));
                }
            }

            @Nested
            @DisplayName("Complex Logic Scenarios")
            class ComplexLogicScenarios {
                @HapiTest
                @DisplayName(
                        "Atomic Batch - Multiple inner txn across File, Crypto, Consensus, Token, Smart Contract services with different inner payers - Fees charged correctly")
                final Stream<DynamicTest>
                        multipleInnerTxnAcrossAllServicesEachWithDifferentInnerPayerFullFeesCharged() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createNonFungibleTokenWithoutCustomFees(NON_FUNGIBLE_TOKEN, OWNER, supplyKey, adminKey),
                            newKeyListNamed("WACL", List.of(PAYER)),
                            cryptoCreate("contractPayer").balance(ONE_HUNDRED_HBARS),
                            uploadInitCode(EMPTY_CONSTRUCTOR_CONTRACT),
                            atomicBatch(
                                            // Service 1 — File: PAYER pays
                                            fileCreate("batchFile")
                                                    .key("WACL")
                                                    .contents("ABC")
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            // Service 2 — Crypto: OWNER pays
                                            cryptoCreate("batchAccount")
                                                    .payingWith(OWNER)
                                                    .signedBy(OWNER)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR),
                                            // Service 3 — Consensus: RECEIVER_ASSOCIATED_FIRST pays
                                            createTopic("batchTopic")
                                                    .payingWith(RECEIVER_ASSOCIATED_FIRST)
                                                    .signedBy(RECEIVER_ASSOCIATED_FIRST)
                                                    .via("innerTxnThird")
                                                    .batchKey(BATCH_OPERATOR),
                                            // Service 4 — Token: RECEIVER_ASSOCIATED_SECOND pays
                                            mintToken(
                                                            NON_FUNGIBLE_TOKEN,
                                                            IntStream.range(10, 11)
                                                                    .mapToObj(i ->
                                                                            ByteString.copyFromUtf8(String.valueOf(i)))
                                                                    .toList())
                                                    .payingWith(RECEIVER_ASSOCIATED_SECOND)
                                                    .signedBy(RECEIVER_ASSOCIATED_SECOND, supplyKey)
                                                    .via("innerTxnFourth")
                                                    .batchKey(BATCH_OPERATOR),
                                            // Service 5 — Smart Contract: contractPayer pays
                                            contractCreate(EMPTY_CONSTRUCTOR_CONTRACT)
                                                    .adminKey(adminKey)
                                                    .payingWith("contractPayer")
                                                    .signedBy("contractPayer", adminKey)
                                                    .via("innerTxnFifth")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", OWNER),
                            validateChargedAccount("innerTxnThird", RECEIVER_ASSOCIATED_FIRST),
                            validateChargedAccount("innerTxnFourth", RECEIVER_ASSOCIATED_SECOND),
                            validateChargedAccount("innerTxnFifth", "contractPayer"),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedFileCreateFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            KEYS, 1L,
                                            STATE_BYTES, 3L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedCryptoCreateFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnThird",
                                    "batchTxn",
                                    txnSize -> expectedTopicCreateFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFourth",
                                    "batchTxn",
                                    txnSize -> expectedTokenMintNftFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            TOKEN_MINT_NFT, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            // ContractCreate fee = simple fees + gas read from the actual record
                            withOpContext((spec, opLog) -> {
                                final var gasUsd =
                                        getChargedGasForContractCreateInnerTxn(spec, "innerTxnFifth", "batchTxn");
                                allRunFor(
                                        spec,
                                        validateInnerChargedUsdWithinWithTxnSize(
                                                "innerTxnFifth",
                                                "batchTxn",
                                                txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                                                SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize))
                                                        + gasUsd,
                                                5.0));
                            })));
                }

                @HapiTest
                @DisplayName(
                        "Atomic Batch - CryptoApproveAllowance and CryptoTransfer using that allowance with the same inner payer - Inner and outer batch payers charged correctly")
                final Stream<DynamicTest>
                        cryptoApproveAllowanceAndCryptoTransferWithAllowanceSameInnerPayerFeeSummedCorrectly() {
                    return hapiTest(flattened(
                            createAccountsAndKeys(),
                            createFungibleTokenWithoutCustomFees(FUNGIBLE_TOKEN, 100L, OWNER, adminKey),
                            tokenAssociate(RECEIVER_ASSOCIATED_FIRST, FUNGIBLE_TOKEN),
                            atomicBatch(
                                            cryptoApproveAllowance()
                                                    .addTokenAllowance(OWNER, FUNGIBLE_TOKEN, PAYER, 10L)
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER, OWNER)
                                                    .via("innerTxnFirst")
                                                    .batchKey(BATCH_OPERATOR),
                                            cryptoTransfer(movingWithAllowance(5L, FUNGIBLE_TOKEN)
                                                            .between(OWNER, RECEIVER_ASSOCIATED_FIRST))
                                                    .payingWith(PAYER)
                                                    .signedBy(PAYER)
                                                    .via("innerTxnSecond")
                                                    .batchKey(BATCH_OPERATOR))
                                    .payingWith(BATCH_OPERATOR)
                                    .signedBy(BATCH_OPERATOR)
                                    .via("batchTxn"),
                            validateChargedAccount("batchTxn", BATCH_OPERATOR),
                            validateChargedAccount("innerTxnFirst", PAYER),
                            validateChargedAccount("innerTxnSecond", PAYER),
                            validateChargedUsdWithinWithTxnSize(
                                    "batchTxn",
                                    txnSize -> expectedAtomicBatchFullFeeUsd(
                                            Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnFirst",
                                    "batchTxn",
                                    txnSize -> expectedCryptoApproveAllowanceFullFeeUsd(Map.of(
                                            SIGNATURES, 2L,
                                            ALLOWANCES, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateInnerChargedUsdWithinWithTxnSize(
                                    "innerTxnSecond",
                                    "batchTxn",
                                    txnSize -> expectedCryptoTransferFTFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            ACCOUNTS, 2L,
                                            TOKEN_TYPES, 1L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1)));
                }
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

    private HapiTokenCreate createNonFungibleTokenWithoutCustomFees(
            String tokenName, String treasury, String supplyKey, String adminKey) {
        return tokenCreate(tokenName)
                .initialSupply(0)
                .treasury(treasury)
                .tokenType(NON_FUNGIBLE_UNIQUE)
                .supplyKey(supplyKey)
                .adminKey(adminKey);
    }

    private HapiTokenMint mintNFT(String tokenName, int rangeStart, int rangeEnd) {
        return mintToken(
                tokenName,
                IntStream.range(rangeStart, rangeEnd)
                        .mapToObj(a -> ByteString.copyFromUtf8(String.valueOf(a)))
                        .toList());
    }

    private List<SpecOperation> createAccountsAndKeys() {
        return List.of(
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(BATCH_OPERATOR).balance(ONE_HUNDRED_HBARS),
                cryptoCreate(RECEIVER_ASSOCIATED_FIRST).balance(ONE_HBAR),
                cryptoCreate(RECEIVER_ASSOCIATED_SECOND).balance(ONE_HBAR),
                newKeyNamed(adminKey),
                newKeyNamed(submitKey),
                newKeyNamed(supplyKey));
    }
}
