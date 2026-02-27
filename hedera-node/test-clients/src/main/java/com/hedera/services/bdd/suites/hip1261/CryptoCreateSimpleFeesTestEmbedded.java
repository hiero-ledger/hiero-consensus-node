// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.spec.queries.QueryVerbs;
import com.hedera.services.bdd.spec.transactions.TxnVerbs;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiSuite;
import com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for CryptoCreate simple fees in embedded mode.
 * Validates that fees are correctly calculated based on:
 * - Number of signatures charged for pre-handle failures
 * - Transaction size contribution to network-fee-only charging
 */
@Tag(ONLY_EMBEDDED)
@Tag(SIMPLE_FEES)
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
public class CryptoCreateSimpleFeesTestEmbedded {
    private static final String PAYER = "payer";
    private static final String PAYER_KEY = "payerKey";
    private static final String TEST_ACCOUNT = "testAccount";
    private static final String CRYPTO_CREATE_TXN_INNER_ID = "crypto-create-txn-inner-id";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("CryptoCreate Simple Fees Failures on Pre-Handle")
    class CryptoCreateSimpleFailuresOnPreHandle {
        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with invalid signature fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInvalidSignatureFailsOnPreHandleNetworkFeeChargedOnly() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl invalidSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.OFF));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HUNDRED_HBARS),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(
                            TokenMovement.movingHbar(HapiSuite.ONE_HBAR).between(HapiSuite.GENESIS, "4")),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, invalidSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(HapiSuite.ONE_HBAR)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INVALID_PAYER_SIGNATURE),
                    QueryVerbs.getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsdWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with insufficient txn fee fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInsufficientTxnFeeFailsOnPreHandleNetworkFeeChargedOnly() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HUNDRED_HBARS),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(
                            TokenMovement.movingHbar(HapiSuite.ONE_HBAR).between(HapiSuite.GENESIS, "4")),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(HapiSuite.ONE_HBAR / 100000)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_TX_FEE),
                    QueryVerbs.getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsdWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with insufficient payer balance fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInsufficientPayerBalanceFailsOnPreHandleNetworkFeeChargedOnly() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HBAR / 100000),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(
                            TokenMovement.movingHbar(HapiSuite.ONE_HBAR).between(HapiSuite.GENESIS, "4")),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(HapiSuite.ONE_HBAR)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE),
                    QueryVerbs.getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsdWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with too long memo fails on pre-handle and no signatures are charged")
        Stream<DynamicTest> cryptoCreateWithTooLongMemoFailsOnPreHandleNetworkFeeChargedOnlyNoSignaturesCharged() {
            final var LONG_MEMO = "x".repeat(1025);
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HUNDRED_HBARS),
                    UtilVerbs.usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID).payerId(PAYER),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(
                            TokenMovement.movingHbar(HapiSuite.ONE_HBAR).between(HapiSuite.GENESIS, "4")),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.cryptoCreate(TEST_ACCOUNT)
                            .memo(LONG_MEMO)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(HapiSuite.ONE_HBAR)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.MEMO_TOO_LONG),
                    QueryVerbs.getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsdWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate expired transaction fails on pre-handle")
        Stream<DynamicTest> cryptoCreateExpiredTransactionFailsOnPreHandleNetworkFeeChargedOnly() {
            final var oneHourBefore = -3_600L;
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HUNDRED_HBARS),
                    UtilVerbs.usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID)
                            .modifyValidStart(oneHourBefore)
                            .payerId(PAYER),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(
                            TokenMovement.movingHbar(HapiSuite.ONE_HBAR).between(HapiSuite.GENESIS, "4")),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(HapiSuite.ONE_HBAR)
                            .setNode(4)
                            .txnId(CRYPTO_CREATE_TXN_INNER_ID)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.TRANSACTION_EXPIRED),
                    QueryVerbs.getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsdWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with too far start time fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithTooFarStartTimeFailsOnPreHandleNetworkFeeChargedOnly() {
            final var oneHourPast = 3_600L;
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HUNDRED_HBARS),
                    UtilVerbs.usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID)
                            .modifyValidStart(oneHourPast)
                            .payerId(PAYER),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(
                            TokenMovement.movingHbar(HapiSuite.ONE_HBAR).between(HapiSuite.GENESIS, "4")),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(HapiSuite.ONE_HBAR)
                            .setNode(4)
                            .txnId(CRYPTO_CREATE_TXN_INNER_ID)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INVALID_TRANSACTION_START),
                    QueryVerbs.getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsdWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with invalid duration time fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInvalidDurationTimeFailsOnPreHandleNetworkFeeChargedOnly() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    UtilVerbs.newKeyNamed(PAYER_KEY).shape(keyShape),
                    TxnVerbs.cryptoCreate(PAYER).key(PAYER_KEY).balance(HapiSuite.ONE_HUNDRED_HBARS),
                    UtilVerbs.usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID).payerId(PAYER),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    TxnVerbs.cryptoTransfer(
                            TokenMovement.movingHbar(HapiSuite.ONE_HBAR).between(HapiSuite.GENESIS, "4")),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    TxnVerbs.cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(HapiSuite.ONE_HBAR)
                            .validDurationSecs(0)
                            .setNode(4)
                            .txnId(CRYPTO_CREATE_TXN_INNER_ID)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INVALID_TRANSACTION_DURATION),
                    QueryVerbs.getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    QueryVerbs.getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    QueryVerbs.getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    UtilVerbs.withOpContext((spec, log) -> {
                        Assertions.assertEquals(initialBalance.get(), afterBalance.get());
                        Assertions.assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    FeesChargingUtils.validateChargedFeeToUsdWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }
    }
}
