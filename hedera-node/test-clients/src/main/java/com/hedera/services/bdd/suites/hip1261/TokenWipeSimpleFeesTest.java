// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingUnique;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenWipeFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenWipeNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for TokenWipe simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenWipeSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ACCOUNT = "account";
    private static final String WIPE_KEY = "wipeKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";
    private static final String NFT_TOKEN = "nftToken";
    private static final String wipeTxn = "wipeTxn";
    private static final String DUPLICATE_TXN_ID = "duplicateWipeTxnId";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenWipe Simple Fees Positive Test Cases")
    class TokenWipePositiveTestCases {

        @HapiTest
        @DisplayName("TokenWipe fungible - base fees")
        final Stream<DynamicTest> tokenWipeFungibleBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .via("wipeTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "wipeTxn",
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenWipe fungible with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenWipeFungibleWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, WIPE_KEY)
                            .via("wipeTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "wipeTxn",
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenWipe fungible with threshold wipe key - extra signatures")
        final Stream<DynamicTest> tokenWipeFungibleWithThresholdWipeKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY).shape(keyShape),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, WIPE_KEY, SUPPLY_KEY)
                            .sigControl(forKey(WIPE_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .sigControl(forKey(WIPE_KEY, validSig))
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(wipeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenWipe fungible - wipe full balance")
        final Stream<DynamicTest> tokenWipeFungibleFullBalance() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(TOKEN, ACCOUNT, 100L) // Wipe entire balance
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(wipeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenWipe fungible with large payer key - extra processing bytes fee")
        final Stream<DynamicTest> tokenWipeFungibleLargeKeyExtraProcessingBytesFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, WIPE_KEY)
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(wipeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenWipe fungible with very large payer key below oversize - extra processing bytes fee")
        final Stream<DynamicTest> tokenWipeFungibleVeryLargeKeyBelowOversizeFee() {
            KeyShape keyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigned = keyShape.signedWith(sigs(
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON,
                    ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .initialSupply(1000L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigned))
                            .signedBy(PAYER, WIPE_KEY)
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 42L, PROCESSING_BYTES, (long) txnSize)),
                            0.1));
        }

        @HapiTest
        @DisplayName("TokenWipe NFT - wipe single serial")
        final Stream<DynamicTest> tokenWipeNftSingleSerial() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("nft-metadata-1")))
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, NFT_TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(NFT_TOKEN, ACCOUNT, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(wipeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenWipe NFT - wipe multiple serials - same fee regardless of serial count")
        final Stream<DynamicTest> tokenWipeNftMultipleSerials() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER),
                    mintToken(
                                    NFT_TOKEN,
                                    List.of(
                                            ByteString.copyFromUtf8("nft-metadata-1"),
                                            ByteString.copyFromUtf8("nft-metadata-2"),
                                            ByteString.copyFromUtf8("nft-metadata-3")))
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, NFT_TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(movingUnique(NFT_TOKEN, 1L, 2L, 3L).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(NFT_TOKEN, ACCOUNT, List.of(1L, 2L, 3L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(wipeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenWipe NFT with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenWipeNftWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("nft-metadata-1")))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, NFT_TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(NFT_TOKEN, ACCOUNT, List.of(1L))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, WIPE_KEY)
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(wipeTxn, PAYER));
        }

        @HapiTest
        @DisplayName("TokenWipe NFT with threshold wipe key - extra signatures")
        final Stream<DynamicTest> tokenWipeNftWithThresholdWipeKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(WIPE_KEY).shape(keyShape),
                    newKeyNamed(SUPPLY_KEY),
                    tokenCreate(NFT_TOKEN)
                            .tokenType(NON_FUNGIBLE_UNIQUE)
                            .initialSupply(0L)
                            .wipeKey(WIPE_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, WIPE_KEY, SUPPLY_KEY)
                            .sigControl(forKey(WIPE_KEY, validSig)),
                    mintToken(NFT_TOKEN, List.of(ByteString.copyFromUtf8("nft-metadata-1")))
                            .payingWith(PAYER),
                    tokenAssociate(ACCOUNT, NFT_TOKEN).payingWith(ACCOUNT),
                    cryptoTransfer(movingUnique(NFT_TOKEN, 1L).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY),
                    wipeTokenAccount(NFT_TOKEN, ACCOUNT, List.of(1L))
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .sigControl(forKey(WIPE_KEY, validSig))
                            .via(wipeTxn),
                    validateChargedUsdWithinWithTxnSize(
                            wipeTxn,
                            txnSize -> expectedTokenWipeFullFeeUsd(
                                    Map.of(SIGNATURES, 3L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(wipeTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("TokenWipe Simple Fees Negative Test Cases")
    class TokenWipeNegativeTestCases {

        @Nested
        @DisplayName("TokenWipe Failures on Ingest and Handle")
        class TokenWipeFailuresOnIngestAndHandle {

            @HapiTest
            @DisplayName("TokenWipe - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .fee(1L) // Fee too low
                                .via(wipeTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(wipeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenWipe - threshold payer key with invalid signature fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeThresholdKeyInvalidSigFailsOnIngest() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl validSig = keyShape.signedWith(sigs(ON, ON));
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, validSig)),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, WIPE_KEY)
                                .via(wipeTxn)
                                .hasPrecheck(INVALID_SIGNATURE),
                        getTxnRecord(wipeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenWipe - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HBAR / 100_000L),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .via(wipeTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(wipeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenWipe - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeMemoTooLongFailsOnIngest() {
                final var LONG_MEMO = "x".repeat(1025);

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .memo(LONG_MEMO)
                                .via(wipeTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(wipeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenWipe - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeExpiredTransactionFailsOnIngest() {
                final var expiredTxnId = "expiredWipeTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .txnId(expiredTxnId)
                                .via(wipeTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(wipeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenWipe - too far start time fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeTooFarStartTimeFailsOnIngest() {
                final var futureTxnId = "futureWipeTxn";

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .txnId(futureTxnId)
                                .via(wipeTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(wipeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenWipe - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeInvalidTransactionDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .validDurationSecs(0)
                                .via(wipeTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(wipeTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("TokenWipe - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeDuplicateTxnFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(200L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .via("wipeFirst"),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .txnId("wipeFirst")
                                .via(wipeTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }

            @HapiTest
            @DisplayName("TokenWipe - missing wipe key signature fails on handle - full fees charged")
            final Stream<DynamicTest> tokenWipeMissingWipeKeySignatureFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing wipe key signature
                                .via(wipeTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                wipeTxn,
                                txnSize -> expectedTokenWipeFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(wipeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenWipe - no wipe key fails on handle - full fees charged")
            final Stream<DynamicTest> tokenWipeNoWipeKeyFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                // No wipe key
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(wipeTxn)
                                .hasKnownStatus(TOKEN_HAS_NO_WIPE_KEY),
                        validateChargedUsdWithinWithTxnSize(
                                wipeTxn,
                                txnSize -> expectedTokenWipeFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(wipeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenWipe - token not associated fails on handle - full fees charged")
            final Stream<DynamicTest> tokenWipeNotAssociatedFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        // Not associating the token
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .via(wipeTxn)
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                wipeTxn,
                                txnSize -> expectedTokenWipeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(wipeTxn, PAYER));
            }

            @HapiTest
            @DisplayName("TokenWipe - invalid wiping amount fails on handle - full fees charged")
            final Stream<DynamicTest> tokenWipeInvalidAmountFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        wipeTokenAccount(TOKEN, ACCOUNT, 200L) // More than account has
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .via(wipeTxn)
                                .hasKnownStatus(INVALID_WIPING_AMOUNT),
                        validateChargedUsdWithinWithTxnSize(
                                wipeTxn,
                                txnSize -> expectedTokenWipeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(wipeTxn, PAYER));
            }

            @LeakyHapiTest
            @Tag(ONLY_SUBPROCESS)
            @DisplayName("TokenWipe - duplicate transaction fails on handle - payer charged for first only")
            final Stream<DynamicTest> tokenWipeDuplicateFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "3")),
                        usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .setNode(4)
                                .txnId(DUPLICATE_TXN_ID)
                                .via(wipeTxn),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .txnId(DUPLICATE_TXN_ID)
                                .setNode(3)
                                .hasPrecheck(DUPLICATE_TRANSACTION),
                        validateChargedUsdWithinWithTxnSize(
                                wipeTxn,
                                txnSize -> expectedTokenWipeFullFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(wipeTxn, PAYER));
            }
        }

        @Nested
        @DisplayName("TokenWipe Failures on Pre-Handle")
        class TokenWipeFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenWipe - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenWipeInvalidPayerSigFailsOnPreHandle() {
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(ACCOUNT).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(WIPE_KEY),
                        newKeyNamed(SUPPLY_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .initialSupply(1000L)
                                .wipeKey(WIPE_KEY)
                                .supplyKey(SUPPLY_KEY)
                                .treasury(TREASURY),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "4")),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, WIPE_KEY)
                                .setNode("4")
                                .via(wipeTxn)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                wipeTxn,
                                txnSize -> expectedTokenWipeNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(wipeTxn, "4"));
            }
        }
    }
}
