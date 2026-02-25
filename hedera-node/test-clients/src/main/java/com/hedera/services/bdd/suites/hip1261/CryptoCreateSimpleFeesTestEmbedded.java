// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
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
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedFeeToUsdWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
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

@Tag(ONLY_EMBEDDED)
@Tag(SIMPLE_FEES)
@TargetEmbeddedMode(CONCURRENT)
@HapiTestLifecycle
public class CryptoCreateSimpleFeesTestEmbedded {
    private static final String PAYER = "payer";
    private static final String PAYER_KEY = "payerKey";

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

            final String INNER_ID = "crypto-create-txn-inner-id";
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, invalidSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .setNode(4)
                            .via(INNER_ID)
                            .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsdWithTxnSize(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with insufficient txn fee fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInsufficientTxnFeeFailsOnPreHandleNetworkFeeChargedOnly() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();

            final String INNER_ID = "crypto-create-txn-inner-id";
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR / 100000)
                            .setNode(4)
                            .via(INNER_ID)
                            .hasKnownStatus(INSUFFICIENT_TX_FEE),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsdWithTxnSize(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with insufficient payer balance fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInsufficientPayerBalanceFailsOnPreHandleNetworkFeeChargedOnly() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();

            final String INNER_ID = "crypto-create-txn-inner-id";
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .setNode(4)
                            .via(INNER_ID)
                            .hasKnownStatus(INSUFFICIENT_PAYER_BALANCE),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsdWithTxnSize(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
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

            final String INNER_ID = "crypto-create-txn-inner-id";
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(INNER_ID).payerId(PAYER),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    cryptoCreate("testAccount")
                            .memo(LONG_MEMO)
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .setNode(4)
                            .via(INNER_ID)
                            .hasKnownStatus(MEMO_TOO_LONG),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsdWithTxnSize(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
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

            final String INNER_ID = "crypto-create-txn-inner-id";
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourBefore).payerId(PAYER),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .setNode(4)
                            .txnId(INNER_ID)
                            .via(INNER_ID)
                            .hasKnownStatus(TRANSACTION_EXPIRED),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsdWithTxnSize(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
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

            final String INNER_ID = "crypto-create-txn-inner-id";
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(INNER_ID).modifyValidStart(oneHourPast).payerId(PAYER),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .setNode(4)
                            .txnId(INNER_ID)
                            .via(INNER_ID)
                            .hasKnownStatus(INVALID_TRANSACTION_START),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsdWithTxnSize(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with invalid duration time fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInvalidDurationTimeFailsOnPreHandleNetworkFeeChargedOnly() {
            final AtomicLong initialBalance = new AtomicLong();
            final AtomicLong afterBalance = new AtomicLong();
            final AtomicLong initialNodeBalance = new AtomicLong();
            final AtomicLong afterNodeBalance = new AtomicLong();

            final String INNER_ID = "crypto-create-txn-inner-id";
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(INNER_ID).payerId(PAYER),
                    getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    getAccountBalance("4").exposingBalanceTo(initialNodeBalance::set),
                    cryptoCreate("testAccount")
                            .key(PAYER_KEY)
                            .sigControl(forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR)
                            .validDurationSecs(0)
                            .setNode(4)
                            .txnId(INNER_ID)
                            .via(INNER_ID)
                            .hasKnownStatus(INVALID_TRANSACTION_DURATION),
                    getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                    getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                    getAccountBalance("4").exposingBalanceTo(afterNodeBalance::set),
                    withOpContext((spec, log) -> {
                        assertEquals(initialBalance.get(), afterBalance.get());
                        assertTrue(initialNodeBalance.get() > afterNodeBalance.get());
                    }),
                    validateChargedFeeToUsdWithTxnSize(
                            INNER_ID,
                            initialNodeBalance,
                            afterNodeBalance,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.01));
        }
    }
}
