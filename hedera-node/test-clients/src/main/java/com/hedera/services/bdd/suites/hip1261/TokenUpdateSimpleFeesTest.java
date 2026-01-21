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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenUpdate;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.DEFAULT_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUpdateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedTokenUpdateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsd;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
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
 * Tests for TokenUpdate simple fees with extras.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures (extras beyond included)
 * - Number of keys being updated
 */
@Tag(MATS)
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class TokenUpdateSimpleFeesTest {

    private static final String PAYER = "payer";
    private static final String TREASURY = "treasury";
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String SUPPLY_KEY = "supplyKey";
    private static final String NEW_SUPPLY_KEY = "newSupplyKey";
    private static final String FREEZE_KEY = "freezeKey";
    private static final String PAYER_KEY = "payerKey";
    private static final String TOKEN = "fungibleToken";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("TokenUpdate Simple Fees Positive Test Cases")
    class TokenUpdateSimpleFeesPositiveTestCases {

        @HapiTest
        @DisplayName("TokenUpdate - base fees without key change")
        final Stream<DynamicTest> tokenUpdateBaseFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenUpdate(TOKEN)
                            .memo("Updated memo")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenUpdateTxn"),
                    validateChargedUsdWithin(
                            "tokenUpdateTxn",
                            expectedTokenUpdateFullFeeUsd(2L, 0L), // 2 sigs, 0 new keys
                            1.0));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with new admin key - extra key and signature")
        final Stream<DynamicTest> tokenUpdateWithNewAdminKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenUpdate(TOKEN)
                            .adminKey(NEW_ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenUpdateTxn"),
                    validateChargedUsdWithin(
                            "tokenUpdateTxn",
                            expectedTokenUpdateFullFeeUsd(3L, 1L), // 3 sigs, 1 new key
                            1.0));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with new supply key - extra key and signature")
        final Stream<DynamicTest> tokenUpdateWithNewSupplyKey() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(NEW_SUPPLY_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenUpdate(TOKEN)
                            .supplyKey(NEW_SUPPLY_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenUpdateTxn"),
                    validateChargedUsdWithin(
                            "tokenUpdateTxn",
                            expectedTokenUpdateFullFeeUsd(2L, 1L), // 2 sigs, 1 new key
                            1.0));
        }

        @HapiTest
        @DisplayName("TokenUpdate - with multiple new keys - multiple extras")
        final Stream<DynamicTest> tokenUpdateWithMultipleNewKeys() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY),
                    newKeyNamed(SUPPLY_KEY),
                    newKeyNamed(NEW_SUPPLY_KEY),
                    newKeyNamed(FREEZE_KEY),
                    newKeyNamed("newFreezeKey"),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .supplyKey(SUPPLY_KEY)
                            .freezeKey(FREEZE_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS),
                    tokenUpdate(TOKEN)
                            .adminKey(NEW_ADMIN_KEY)
                            .supplyKey(NEW_SUPPLY_KEY)
                            .freezeKey("newFreezeKey")
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenUpdateTxn"),
                    validateChargedUsdWithin(
                            "tokenUpdateTxn",
                            expectedTokenUpdateFullFeeUsd(3L, 3L), // 3 sigs, 3 new keys
                            1.0));
        }

        @HapiTest
        @DisplayName("TokenUpdate with threshold key - extra signatures")
        final Stream<DynamicTest> tokenUpdateWithThresholdKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(TREASURY).balance(0L),
                    newKeyNamed(ADMIN_KEY),
                    tokenCreate(TOKEN)
                            .tokenType(FUNGIBLE_COMMON)
                            .adminKey(ADMIN_KEY)
                            .treasury(TREASURY)
                            .payingWith(PAYER)
                            .fee(ONE_HUNDRED_HBARS)
                            .sigControl(forKey(PAYER_KEY, validSig)),
                    tokenUpdate(TOKEN)
                            .memo("Updated memo")
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .signedBy(PAYER, ADMIN_KEY)
                            .fee(ONE_HUNDRED_HBARS)
                            .via("tokenUpdateTxn"),
                    validateChargedUsdWithin(
                            "tokenUpdateTxn",
                            expectedTokenUpdateFullFeeUsd(3L, 0L), // 3 sigs (2 payer + 1 admin)
                            1.0));
        }
    }

    @Nested
    @DisplayName("TokenUpdate Simple Fees Negative Test Cases")
    class TokenUpdateSimpleFeesNegativeTestCases {

        @Nested
        @DisplayName("TokenUpdate Failures on Ingest")
        class TokenUpdateFailuresOnIngest {

            @HapiTest
            @DisplayName("TokenUpdate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> tokenUpdateInsufficientTxFeeFailsOnIngest() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .payingWith(PAYER)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L) // Fee too low
                                .via("tokenUpdateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("tokenUpdateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),
                        getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                        withOpContext((spec, log) -> {
                            assertEquals(initialBalance.get(), afterBalance.get());
                        }));
            }

            @HapiTest
            @DisplayName("TokenUpdate - invalid token fails")
            final Stream<DynamicTest> tokenUpdateInvalidTokenFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        tokenUpdate("0.0.99999999") // Invalid token
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .fee(ONE_HUNDRED_HBARS)
                                .hasKnownStatus(INVALID_TOKEN_ID));
            }
        }

        @Nested
        @DisplayName("TokenUpdate Failures on Pre-Handle")
        class TokenUpdateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("TokenUpdate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> tokenUpdateInvalidPayerSigFailsOnPreHandle() {
                final AtomicLong initialBalance = new AtomicLong();
                final AtomicLong afterBalance = new AtomicLong();
                final AtomicLong initialNodeBalance = new AtomicLong();
                final AtomicLong afterNodeBalance = new AtomicLong();

                final String INNER_ID = "token-update-txn-inner-id";

                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(TREASURY).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        tokenCreate(TOKEN)
                                .tokenType(FUNGIBLE_COMMON)
                                .adminKey(ADMIN_KEY)
                                .treasury(TREASURY)
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(DEFAULT_PAYER, "0.0.4"))
                                .fee(ONE_HUNDRED_HBARS),
                        getAccountBalance("0.0.4").exposingBalanceTo(initialNodeBalance::set),
                        tokenUpdate(TOKEN)
                                .memo("Updated memo")
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
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
                                expectedTokenUpdateNetworkFeeOnlyUsd(2L),
                                1.0));
            }
        }
    }
}
