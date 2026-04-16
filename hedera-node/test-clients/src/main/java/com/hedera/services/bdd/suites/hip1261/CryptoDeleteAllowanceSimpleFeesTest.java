// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.TestTags.ONLY_SUBPROCESS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDeleteAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.allOnSigControl;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoDeleteAllowanceFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.signedTxnSizeFor;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.thresholdKeyWithPrimitives;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesScheduleConstantsInUsd.NODE_INCLUDED_BYTES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ALLOWANCE_OWNER_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_OVERSIZE;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.ALLOWANCES;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.AccountID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
@DisplayName("CryptoDeleteAllowance Simple Fees")
public class CryptoDeleteAllowanceSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String OWNER = "owner";
    private static final String SPENDER = "spender";
    private static final String NFT_TOKEN = "nftToken";
    private static final String NFT_TOKEN_SECOND = "nftTokenSecond";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String deleteAllowanceTxn = "deleteAllowanceTxn";
    private static final String DUPLICATE_TXN_ID = "duplicateTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("CryptoDeleteAllowance Simple Fees Positive Test Cases")
    class CryptoDeleteAllowanceSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("CryptoDeleteAllowance - single NFT serial allowance - base fee only")
        final Stream<DynamicTest> cryptoDeleteAllowanceSingleNftSerialBaseFeesOnly() {
            return hapiTest(
                    newKeyNamed(SUPPLY_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(OWNER),
                    tokenAssociate(SPENDER, NFT_TOKEN),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                    cryptoApproveAllowance()
                            .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER),
                    cryptoDeleteAllowance()
                            .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(deleteAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            deleteAllowanceTxn,
                            txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(deleteAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDeleteAllowance - two NFT serials allowance - base fee charged")
        final Stream<DynamicTest> cryptoDeleteAllowanceTwoNftSerialsBaseFeesOnly() {
            return hapiTest(
                    newKeyNamed(SUPPLY_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(OWNER),
                    tokenAssociate(SPENDER, NFT_TOKEN),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"), ByteString.copyFromUtf8("meta2"))),
                    cryptoApproveAllowance()
                            .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L, 2L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER),
                    cryptoDeleteAllowance()
                            .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L, 2L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(deleteAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            deleteAllowanceTxn,
                            txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(deleteAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDeleteAllowance - five NFT serials allowance - base fee charged")
        final Stream<DynamicTest> cryptoDeleteAllowanceFiveNftSerialsBaseFeesOnly() {
            return hapiTest(
                    newKeyNamed(SUPPLY_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(OWNER),
                    tokenAssociate(SPENDER, NFT_TOKEN),
                    mintToken(
                            NFT_TOKEN,
                            List.of(
                                    ByteString.copyFromUtf8("m1"),
                                    ByteString.copyFromUtf8("m2"),
                                    ByteString.copyFromUtf8("m3"),
                                    ByteString.copyFromUtf8("m4"),
                                    ByteString.copyFromUtf8("m5"))),
                    cryptoApproveAllowance()
                            .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L, 2L, 3L, 4L, 5L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER),
                    cryptoDeleteAllowance()
                            .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L, 2L, 3L, 4L, 5L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(deleteAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            deleteAllowanceTxn,
                            txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(deleteAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDeleteAllowance - two NFT tokens allowance - extra fees charged")
        final Stream<DynamicTest> cryptoDeleteAllowanceTwoNftTokensExtraCharged() {
            return hapiTest(
                    newKeyNamed(SUPPLY_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(OWNER),
                    tokenCreate(NFT_TOKEN_SECOND)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(OWNER),
                    tokenAssociate(SPENDER, NFT_TOKEN, NFT_TOKEN_SECOND),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"), ByteString.copyFromUtf8("meta2"))),
                    mintToken(
                            NFT_TOKEN_SECOND,
                            List.of(ByteString.copyFromUtf8("meta1"), ByteString.copyFromUtf8("meta2"))),
                    cryptoApproveAllowance()
                            .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L, 2L))
                            .addNftAllowance(OWNER, NFT_TOKEN_SECOND, SPENDER, false, List.of(1L, 2L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER),
                    cryptoDeleteAllowance()
                            .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L, 2L))
                            .addNftDeleteAllowance(OWNER, NFT_TOKEN_SECOND, List.of(1L, 2L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(deleteAllowanceTxn),
                    validateChargedUsdWithinWithTxnSize(
                            deleteAllowanceTxn,
                            txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    ALLOWANCES, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(deleteAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName("CryptoDeleteAllowance - txn above NODE_INCLUDED_BYTES - extra PROCESSING_BYTES fees charged")
        final Stream<DynamicTest> cryptoDeleteAllowanceAboveProcessingBytesThresholdExtrasCharged() {
            return hapiTest(
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(20)),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(OWNER),
                    tokenAssociate(SPENDER, NFT_TOKEN),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                    cryptoApproveAllowance()
                            .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER),
                    cryptoDeleteAllowance()
                            .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(20)))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(deleteAllowanceTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, deleteAllowanceTxn);
                        log.info("Large-key CryptoDeleteAllowance signed size: {} bytes", txnSize);
                        assertTrue(
                                txnSize > NODE_INCLUDED_BYTES,
                                "Expected txn size to exceed NODE_INCLUDED_BYTES (" + NODE_INCLUDED_BYTES + "), was "
                                        + txnSize);
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            deleteAllowanceTxn,
                            txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 21L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(deleteAllowanceTxn, PAYER));
        }

        @HapiTest
        @DisplayName(
                "CryptoDeleteAllowance - very large txn (just below 6KB) - full charging with extra PROCESSING_BYTES")
        final Stream<DynamicTest> cryptoDeleteAllowanceVeryLargeTxnJustBelow6KBExtraCharged() {
            return hapiTest(
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(55)),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(OWNER),
                    tokenAssociate(SPENDER, NFT_TOKEN),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                    cryptoApproveAllowance()
                            .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER),
                    cryptoDeleteAllowance()
                            .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                            .sigControl(forKey(PAYER_KEY, allOnSigControl(55)))
                            .payingWith(PAYER)
                            .signedBy(PAYER, OWNER)
                            .via(deleteAllowanceTxn),
                    assertionsHold((spec, log) -> {
                        final int txnSize = signedTxnSizeFor(spec, deleteAllowanceTxn);
                        log.info("Very-large CryptoDeleteAllowance signed size: {} bytes", txnSize);
                        assertTrue(txnSize < 6_000, "Expected txn size (" + txnSize + ") to not exceed 6000 bytes");
                    }),
                    validateChargedUsdWithinWithTxnSize(
                            deleteAllowanceTxn,
                            txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                    SIGNATURES, 56L,
                                    ALLOWANCES, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(deleteAllowanceTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("CryptoDeleteAllowance Simple Fees Negative Test Cases")
    class CryptoDeleteAllowanceSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("CryptoDeleteAllowance Simple Fees Failures on Ingest")
        class CryptoDeleteAllowanceSimpleFeesFailuresOnIngest {

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - threshold key with invalid signature - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceThresholdKeyInvalidSignatureFailsOnIngest() {
                final KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                final SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, OWNER)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - key list with missing signature - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceKeyListMissingSignatureFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed("firstKey"),
                        newKeyNamed("secondKey"),
                        newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy("firstKey", OWNER)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - insufficient tx fee - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .fee(ONE_HBAR / 100_000)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - insufficient payer balance fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - memo too long - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .memo(LONG_MEMO)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - expired transaction - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredDeleteAllowanceTxn";
                final var oneHourPast = -3_600L;
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        usableTxnIdNamed(expiredTxnId)
                                .modifyValidStart(oneHourPast)
                                .payerId(PAYER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(expiredTxnId)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - too far start time - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureTxnId";
                final var oneHourAhead = 3_600L;
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        usableTxnIdNamed(futureTxnId)
                                .modifyValidStart(oneHourAhead)
                                .payerId(PAYER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(futureTxnId)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - invalid duration - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceInvalidDurationFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .validDurationSecs(0)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - duplicate transaction - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .via(deleteAllowanceTxn),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(deleteAllowanceTxn)
                                .via("deleteAllowanceDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - very large txn (above 6KB) - fails on ingest")
            final Stream<DynamicTest> cryptoDeleteAllowanceVeryLargeTxnAboveSixKBFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(PAYER_KEY).shape(thresholdKeyWithPrimitives(71)),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(OWNER)
                                .signedBy(OWNER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .sigControl(forKey(PAYER_KEY, allOnSigControl(71)))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .via(deleteAllowanceTxn)
                                .hasPrecheck(TRANSACTION_OVERSIZE),
                        getTxnRecord(deleteAllowanceTxn).logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("CryptoDeleteAllowance Simple Fees Failures on Handle")
        class CryptoDeleteAllowanceSimpleFeesFailuresOnHandle {

            @Tag(ONLY_SUBPROCESS)
            @LeakyHapiTest
            @DisplayName("CryptoDeleteAllowance - duplicate transaction fails on handle - payer charged full fee")
            final Stream<DynamicTest> cryptoDeleteAllowanceDuplicateTransactionFailsOnHandlePayerChargedFullFee() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        cryptoApproveAllowance()
                                .addNftAllowance(OWNER, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(deleteAllowanceTxn)
                                .logged(),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(OWNER, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .via("deleteAllowanceDuplicateTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                deleteAllowanceTxn,
                                txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        ALLOWANCES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(deleteAllowanceTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - deleted owner fails on handle - full fees charged")
            final Stream<DynamicTest> cryptoDeleteAllowanceDeletedOwnerFailsOnHandle() {
                final String THIRD_ACCOUNT = "thirdAccount";
                final String THIRD_KEY = "thirdKey";
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        newKeyNamed(THIRD_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(SPENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(THIRD_ACCOUNT).key(THIRD_KEY).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        tokenAssociate(THIRD_ACCOUNT, NFT_TOKEN),
                        tokenAssociate(SPENDER, NFT_TOKEN),
                        mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("meta1"))),
                        // OWNER transfers serial to THIRD_ACCOUNT
                        cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(OWNER, THIRD_ACCOUNT))
                                .payingWith(PAYER)
                                .signedBy(PAYER, OWNER),
                        // approve allowance from THIRD_ACCOUNT
                        cryptoApproveAllowance()
                                .addNftAllowance(THIRD_ACCOUNT, NFT_TOKEN, SPENDER, false, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, THIRD_KEY),
                        // Transfer NFT back to OWNER so THIRD_ACCOUNT can be deleted
                        cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(THIRD_ACCOUNT, OWNER))
                                .payingWith(PAYER)
                                .signedBy(PAYER, THIRD_KEY),
                        cryptoDelete(THIRD_ACCOUNT).transfer(PAYER).payingWith(PAYER),
                        // Try to delete the allowance
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance(THIRD_ACCOUNT, NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER, THIRD_ACCOUNT)
                                .via(deleteAllowanceTxn)
                                .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID),
                        validateChargedUsdWithinWithTxnSize(
                                deleteAllowanceTxn,
                                txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                        SIGNATURES, 2L,
                                        ALLOWANCES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(deleteAllowanceTxn, PAYER));
            }

            @HapiTest
            @DisplayName("CryptoDeleteAllowance - non-existent owner fails on handle - full fees charged")
            final Stream<DynamicTest> cryptoDeleteAllowanceNonExistentOwnerFailsOnHandle() {
                return hapiTest(
                        newKeyNamed(SUPPLY_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(OWNER).balance(ONE_HUNDRED_HBARS),
                        tokenCreate(NFT_TOKEN)
                                .tokenType(NON_FUNGIBLE_UNIQUE)
                                .initialSupply(0)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(OWNER),
                        // register a non-existent account ID in the registry
                        withOpContext((spec, log) -> spec.registry()
                                .saveAccountId(
                                        "nonExistentOwner",
                                        AccountID.newBuilder()
                                                .setShardNum(0)
                                                .setRealmNum(0)
                                                .setAccountNum(9_999_999L)
                                                .build())),
                        cryptoDeleteAllowance()
                                .addNftDeleteAllowance("nonExistentOwner", NFT_TOKEN, List.of(1L))
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(deleteAllowanceTxn)
                                .hasKnownStatus(INVALID_ALLOWANCE_OWNER_ID),
                        validateChargedUsdWithinWithTxnSize(
                                deleteAllowanceTxn,
                                txnSize -> expectedCryptoDeleteAllowanceFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        ALLOWANCES, 1L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(deleteAllowanceTxn, PAYER));
            }
        }
    }
}
