// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.services.bdd.junit.EmbeddedReason.MUST_SKIP_INGEST;
import static com.hedera.services.bdd.junit.TestTags.ONLY_EMBEDDED;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.CONCURRENT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedCryptoCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdFromRecordWithTxnSize;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.ControlForKey;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
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
public class CryptoCreateSimpleFeesEmbeddedTest {
    private static final String PAYER = "payer";
    private static final String PAYER_KEY = "payerKey";
    private static final String TEST_ACCOUNT = "testAccount";
    private static final String CRYPTO_CREATE_TXN_INNER_ID = "crypto-create-txn-inner-id";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("CryptoCreate Simple Fees Failures on Pre-Handle")
    class CryptoCreateSimpleFailuresOnPreHandle {
        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with invalid signature fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInvalidSignatureFailsOnPreHandleNetworkFeeChargedOnly() {
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl invalidSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.OFF));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, invalidSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INVALID_PAYER_SIGNATURE),
                    getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    validateChargedUsdFromRecordWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
                            0.1),
                    validateChargedAccount(CRYPTO_CREATE_TXN_INNER_ID, "0.0.4"));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with insufficient txn fee fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInsufficientTxnFeeFailsOnPreHandleNetworkFeeChargedOnly() {
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .fee(ONE_HBAR / 100000)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_TX_FEE),
                    getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    validateChargedUsdFromRecordWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.1),
                    validateChargedAccount(CRYPTO_CREATE_TXN_INNER_ID, "0.0.4"));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with insufficient payer balance fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInsufficientPayerBalanceFailsOnPreHandleNetworkFeeChargedOnly() {
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HBAR / 100000),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE),
                    getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    validateChargedUsdFromRecordWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.1),
                    validateChargedAccount(CRYPTO_CREATE_TXN_INNER_ID, "0.0.4"));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with too long memo fails on pre-handle and no signatures are charged")
        Stream<DynamicTest> cryptoCreateWithTooLongMemoFailsOnPreHandleNetworkFeeChargedOnlyNoSignaturesCharged() {
            final var LONG_MEMO = "x".repeat(1025);
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID).payerId(PAYER),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    cryptoCreate(TEST_ACCOUNT)
                            .memo(LONG_MEMO)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .setNode(4)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.MEMO_TOO_LONG),
                    getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    validateChargedUsdFromRecordWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(1L, txnSize),
                            0.1),
                    validateChargedAccount(CRYPTO_CREATE_TXN_INNER_ID, "0.0.4"));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate expired transaction fails on pre-handle")
        Stream<DynamicTest> cryptoCreateExpiredTransactionFailsOnPreHandleNetworkFeeChargedOnly() {
            final var oneHourBefore = -3_600L;
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID)
                            .modifyValidStart(oneHourBefore)
                            .payerId(PAYER),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .setNode(4)
                            .txnId(CRYPTO_CREATE_TXN_INNER_ID)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.TRANSACTION_EXPIRED),
                    getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    validateChargedUsdFromRecordWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.1),
                    validateChargedAccount(CRYPTO_CREATE_TXN_INNER_ID, "0.0.4"));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with too far start time fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithTooFarStartTimeFailsOnPreHandleNetworkFeeChargedOnly() {
            final var oneHourPast = 3_600L;
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID)
                            .modifyValidStart(oneHourPast)
                            .payerId(PAYER),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .setNode(4)
                            .txnId(CRYPTO_CREATE_TXN_INNER_ID)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INVALID_TRANSACTION_START),
                    getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    validateChargedUsdFromRecordWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.1),
                    validateChargedAccount(CRYPTO_CREATE_TXN_INNER_ID, "0.0.4"));
        }

        @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
        @DisplayName("CryptoCreate with invalid duration time fails on pre-handle")
        Stream<DynamicTest> cryptoCreateWithInvalidDurationTimeFailsOnPreHandleNetworkFeeChargedOnly() {
            final KeyShape keyShape = KeyShape.threshOf(2, KeyShape.SIMPLE, KeyShape.SIMPLE);
            final SigControl validSig = keyShape.signedWith(KeyShape.sigs(SigControl.ON, SigControl.ON));

            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(keyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    usableTxnIdNamed(CRYPTO_CREATE_TXN_INNER_ID).payerId(PAYER),
                    cryptoTransfer(movingHbar(ONE_HBAR).between(GENESIS, "4")),
                    cryptoCreate(TEST_ACCOUNT)
                            .key(PAYER_KEY)
                            .sigControl(ControlForKey.forKey(PAYER_KEY, validSig))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .validDurationSecs(0)
                            .setNode(4)
                            .txnId(CRYPTO_CREATE_TXN_INNER_ID)
                            .via(CRYPTO_CREATE_TXN_INNER_ID)
                            .hasKnownStatus(ResponseCodeEnum.INVALID_TRANSACTION_DURATION),
                    getTxnRecord(CRYPTO_CREATE_TXN_INNER_ID)
                            .assertingNothingAboutHashes()
                            .logged(),
                    validateChargedUsdFromRecordWithTxnSize(
                            CRYPTO_CREATE_TXN_INNER_ID,
                            txnSize -> expectedCryptoCreateNetworkFeeOnlyUsd(2L, txnSize),
                            0.1),
                    validateChargedAccount(CRYPTO_CREATE_TXN_INNER_ID, "0.0.4"));
        }
    }
}
