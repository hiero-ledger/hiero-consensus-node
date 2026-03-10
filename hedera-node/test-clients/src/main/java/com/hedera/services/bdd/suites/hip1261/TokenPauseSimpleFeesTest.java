// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenPause;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUnpause;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenPauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenPauseNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUnpauseFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_PAUSE_KEY;
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
 * Tests for TokenPause and TokenUnpause simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenPauseSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String PAUSE_KEY = "pauseKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenPause Simple Fees Positive Test Cases")
    class TokenPausePositiveTestCases {

        @HapiTest
        @DisplayName("TokenPause - base fees")
        final Stream<DynamicTest> tokenPauseBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, PAUSE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("pauseTxn"),
                    validateChargedUsdWithin(
                            "pauseTxn",
                            expectedTokenPauseFullFeeUsd(2L), // 2 sigs
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenPause with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenPauseWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, PAUSE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("pauseTxn"),
                    validateChargedUsdWithin(
                            "pauseTxn",
                            expectedTokenPauseFullFeeUsd(3L), // 3 sigs (2 payer + 1 pause key)
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenPause with threshold pause key - extra signatures")
        final Stream<DynamicTest> tokenPauseWithThresholdPauseKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY).shape(keyShape),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, TREASURY, PAUSE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAUSE_KEY, validSig)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, PAUSE_KEY)
                            .sigControl(forKey(PAUSE_KEY, validSig))
                            .fee(ONE_HUNDRED_HBARS)
                            .via("pauseTxn"),
                    validateChargedUsdWithin(
                            "pauseTxn",
                            expectedTokenPauseFullFeeUsd(3L), // 3 sigs (1 payer + 2 pause key)
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenUnpause Simple Fees Positive Test Cases")
    class TokenUnpausePositiveTestCases {

        @HapiTest
        @DisplayName("TokenUnpause - base fees")
        final Stream<DynamicTest> tokenUnpauseBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, PAUSE_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenUnpause(TOKEN)
                            .payingWith(PAYER)
                            .signedBy(PAYER, PAUSE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("unpauseTxn"),
                    validateChargedUsdWithin(
                            "unpauseTxn",
                            expectedTokenUnpauseFullFeeUsd(2L), // 2 sigs
                            0.001));
        }

        @HapiTest
        @DisplayName("TokenUnpause with threshold payer key - extra signatures")
        final Stream<DynamicTest> tokenUnpauseWithThresholdPayerKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(PAUSE_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .pauseKey(PAUSE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenPause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, PAUSE_KEY)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenUnpause(TOKEN)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, PAUSE_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("unpauseTxn"),
                    validateChargedUsdWithin(
                            "unpauseTxn",
                            expectedTokenUnpauseFullFeeUsd(3L), // 3 sigs (2 payer + 1 pause key)
                            0.001));
        }
    }

    @Nested
    @DisplayName("TokenPause Simple Fees Negative Test Cases")
    class TokenPauseNegativeTestCases {

        @Nested
        @DisplayName("TokenPause Failures on Ingest and Handle")
        class TokenPauseFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenPause - missing pause key signature fails")
            final Stream<DynamicTest> tokenPauseMissingPauseKeySignatureFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing pause key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("pauseTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "pauseTxn", initialBalance, afterBalance, expectedTokenPauseFullFeeUsd(1L), 0.001));
            }

            @HapiTest
            @DisplayName("TokenPause - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenPauseInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .fee(1L) // Fee too low
                                .via("pauseTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("pauseTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenPause - no pause key fails")
            final Stream<DynamicTest> tokenPauseNoPauseKeyFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No pause key
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("pauseTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "pauseTxn", initialBalance, afterBalance, expectedTokenPauseFullFeeUsd(1L), 0.001));
            }
        }

        @Nested
        @DisplayName("TokenPause Failures on Pre-Handle")
        class TokenPauseFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenPause - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenPauseInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "pause-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, PAUSE_KEY)
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
                                expectedTokenPauseNetworkFeeOnlyUsd(2L),
                                0.001));
            }
        }
    }

    @Nested
    @DisplayName("TokenUnpause Simple Fees Negative Test Cases")
    class TokenUnpauseNegativeTestCases {

        @Nested
        @DisplayName("TokenUnpause Failures on Ingest and Handle")
        class TokenUnpauseFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenUnpause - missing pause key signature fails")
            final Stream<DynamicTest> tokenUnpauseMissingPauseKeySignatureFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // Missing pause key signature
                                .fee(ONE_HUNDRED_HBARS)
                                .via("unpauseTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "unpauseTxn", initialBalance, afterBalance, expectedTokenUnpauseFullFeeUsd(1L), 0.001));
            }

            @HapiTest
            @DisplayName("TokenUnpause - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUnpauseInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(PAUSE_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .pauseKey(PAUSE_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        tokenPause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER, PAUSE_KEY)
                                .fee(1L) // Fee too low
                                .via("unpauseTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("unpauseTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenUnpause - no pause key fails")
            final Stream<DynamicTest> tokenUnpauseNoPauseKeyFails() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                // No pause key
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenUnpause(TOKEN)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .via("unpauseTxn")
                                .hasKnownStatus(TOKEN_HAS_NO_PAUSE_KEY),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertTrue(initialBalance.get() > afterBalance.get());
                        }),
                        validateChargedFeeToUsd(
                                "unpauseTxn", initialBalance, afterBalance, expectedTokenUnpauseFullFeeUsd(1L), 0.001));
            }
        }
    }
}
