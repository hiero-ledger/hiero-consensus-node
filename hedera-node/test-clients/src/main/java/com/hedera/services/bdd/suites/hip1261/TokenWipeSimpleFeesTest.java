// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.MATS;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.wipeTokenAccount;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenWipeFungibleFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenWipeNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.TokenType.FUNGIBLE_COMMON;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
@Tag(MATS)
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY)
                            .fee(ONE_HUNDRED_HBARS),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("wipeTxn"),
                    validateChargedUsdWithin(
                            "wipeTxn",
                            expectedTokenWipeFungibleFullFeeUsd(2L), // 2 sigs
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY)
                            .fee(ONE_HUNDRED_HBARS),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, WIPE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("wipeTxn"),
                    validateChargedUsdWithin(
                            "wipeTxn",
                            expectedTokenWipeFungibleFullFeeUsd(3L), // 3 sigs (2 payer + 1 wipe key)
                            0.001));
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
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(WIPE_KEY, validSig)),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY)
                            .fee(ONE_HUNDRED_HBARS),
                    wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .sigControl(forKey(WIPE_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("wipeTxn"),
                    validateChargedUsdWithin(
                            "wipeTxn",
                            expectedTokenWipeFungibleFullFeeUsd(3L), // 3 sigs (1 payer + 2 wipe key)
                            0.001));
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
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                    cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                            .payingWith(TREASURY)
                            .fee(ONE_HUNDRED_HBARS),
                    wipeTokenAccount(TOKEN, ACCOUNT, 100L) // Wipe entire balance
                            .payingWith(PAYER)
                            .signedBy(PAYER, WIPE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("wipeTxn"),
                    validateChargedUsdWithin(
                            "wipeTxn",
                            expectedTokenWipeFungibleFullFeeUsd(2L), // 2 sigs
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenWipe Simple Fees Negative Test Cases")
    class TokenWipeNegativeTestCases {

        @Nested
        @DisplayName("TokenWipe Failures on Ingest and Handle")
        class TokenWipeFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenWipe - missing wipe key signature fails")
            final Stream<DynamicTest> tokenWipeMissingWipeKeySignatureFailsAtHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing wipe key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("wipeTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "wipeTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenWipeFungibleFullFeeUsd(1L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenWipe - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenWipeInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .fee(1L) // Fee too low
                                .via("wipeTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("wipeTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenWipe - no wipe key fails")
            final Stream<DynamicTest> tokenWipeNoWipeKeyFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("wipeTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_WIPE_KEY),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "wipeTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenWipeFungibleFullFeeUsd(1L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenWipe - token not associated fails")
            final Stream<DynamicTest> tokenWipeNotAssociatedFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        // Not associating the token
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("wipeTxn")
                                .hasKnownStatus(TOKEN_NOT_ASSOCIATED_TO_ACCOUNT),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "wipeTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenWipeFungibleFullFeeUsd(2L),
                                0.001));
            }

            @HapiTest
            @DisplayName("TokenWipe - invalid wiping amount fails")
            final Stream<DynamicTest> tokenWipeInvalidAmountFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

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
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        wipeTokenAccount(TOKEN, ACCOUNT, 200L) // More than account has
                                .payingWith(PAYER)
                                .signedBy(PAYER, WIPE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("wipeTxn")
                                .hasKnownStatus(INVALID_WIPING_AMOUNT),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "wipeTxn",
                                initialBalance,
                                afterBalance,
                                expectedTokenWipeFungibleFullFeeUsd(2L),
                                0.001));
            }
        }

        @Nested
        @DisplayName("TokenWipe Failures on Pre-Handle")
        class TokenWipeFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenWipe - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenWipeInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "wipe-txn-inner-id";

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
                                .treasury(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenAssociate(ACCOUNT, TOKEN).payingWith(ACCOUNT).fee(ONE_HUNDRED_HBARS),
                        cryptoTransfer(moving(100L, TOKEN).between(TREASURY, ACCOUNT))
                                .payingWith(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        wipeTokenAccount(TOKEN, ACCOUNT, 50L)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, WIPE_KEY)
                                .fee(ONE_HUNDRED_HBARS)
                                .setNode("0.0.4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        getAccountBalance("0.0.4").exposingBalanceTo(afterNodeBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                            assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                INNER_ID,
                                initialNodeBalance,
                                afterNodeBalance,
                                expectedTokenWipeNetworkFeeOnlyUsd(2L),
                                0.001));
            }
        }
    }
}
