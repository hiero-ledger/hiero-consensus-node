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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdForQueries;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateNodePaymentAmountForQuery;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleCreateContractCallFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleCreateFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleCreateNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleDeleteFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleDeleteNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleGetInfoNodePaymentTinycents;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleGetInfoQueryFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleSignFullFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedScheduleSignNetworkFeeOnlyUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hedera.services.bdd.suites.schedule.ScheduleUtils.SIMPLE_UPDATE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

/**
 * Tests for Schedule service operations with simple fees.
 * Operations covered:
 *   ScheduleCreate — KEYS extra (includedCount=1), SCHEDULE_CREATE_CONTRACT_CALL_BASE extra for contract calls
 *   ScheduleSign   — node extras, fixed service fee
 *   ScheduleDelete — node extras, fixed service fee
 *   ScheduleGetInfo — query, fixed service fee
 */
@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class ScheduleServiceSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String SENDER = "sender";
    private static final String RECEIVER = "receiver";
    private static final String NEW_PAYER = "newPayer";
    private static final String SCHEDULE = "schedule";
    private static final String PAYER_KEY = "payerKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String scheduleCreateTxn = "scheduleCreateTxn";
    private static final String scheduleSignTxn = "scheduleSignTxn";
    private static final String scheduleDeleteTxn = "scheduleDeleteTxn";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of("fees.simpleFeesEnabled", "true"));
    }

    @Nested
    @DisplayName("ScheduleCreate Simple Fees Positive Test Cases")
    class ScheduleCreatePositiveTestCases {
        @HapiTest
        @DisplayName("ScheduleCreate - base fee (no admin key, 1 included key)")
        final Stream<DynamicTest> scheduleCreateBaseFee() {
            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .payingWith(PAYER)
                            .via(scheduleCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleCreateTxn,
                            txnSize -> expectedScheduleCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleCreateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("ScheduleCreate - with admin key (1 included key, 0 extra)")
        final Stream<DynamicTest> scheduleCreateWithAdminKey() {
            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(scheduleCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleCreateTxn,
                            txnSize -> expectedScheduleCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 2L,
                                    KEYS, 1L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleCreateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("ScheduleCreate - with threshold admin key (extra sigs)")
        final Stream<DynamicTest> scheduleCreateWithThresholdAdminKey() {
            KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
            SigControl validSig = keyShape.signedWith(sigs(ON, ON));

            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY).shape(keyShape),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .sigControl(forKey(ADMIN_KEY, validSig))
                            .via(scheduleCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleCreateTxn,
                            txnSize -> expectedScheduleCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 3L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount("scheduleCreateTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ScheduleCreate - large payer key charges extra signatures and processing bytes")
        final Stream<DynamicTest> scheduleCreateLargePayerKeyExtraFee() {
            KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = largeKeyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER)
                            .via(scheduleCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleCreateTxn,
                            txnSize -> expectedScheduleCreateFullFeeUsd(Map.of(
                                    SIGNATURES, 20L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleCreateTxn, PAYER));
        }

        @HapiTest
        @DisplayName("ScheduleCreate - scheduled contract call charges SCHEDULE_CREATE_CONTRACT_CALL_BASE extra")
        final Stream<DynamicTest> scheduleCreateContractCallExtraFee() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    uploadInitCode(SIMPLE_UPDATE),
                    contractCreate(SIMPLE_UPDATE).gas(300_000L),
                    scheduleCreate(SCHEDULE, contractCall(SIMPLE_UPDATE))
                            .payingWith(PAYER)
                            .signedBy(PAYER)
                            .via(scheduleCreateTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleCreateTxn,
                            txnSize -> expectedScheduleCreateContractCallFullFeeUsd(Map.of(
                                    SIGNATURES, 1L,
                                    KEYS, 0L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleCreateTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("ScheduleCreate Simple Fees Negative Test Cases")
    class ScheduleCreateNegativeTestCases {

        @Nested
        @DisplayName("ScheduleCreate Failures on Ingest")
        class ScheduleCreateFailuresOnIngest {

            @HapiTest
            @DisplayName("ScheduleCreate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleCreateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .fee(1L) // insufficient fee
                                .via(scheduleCreateTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(scheduleCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleCreate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleCreateInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(0L),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .via(scheduleCreateTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(scheduleCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleCreate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleCreateMemoTooLongFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .memo("x".repeat(101))
                                .via(scheduleCreateTxn)
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord(scheduleCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleCreate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleCreateExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredScheduleCreateTxn";
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .txnId(expiredTxnId)
                                .via(scheduleCreateTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(scheduleCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleCreate - too far in future fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleCreateTooFarInFutureFailsOnIngest() {
                final var futureTxnId = "futureScheduleCreateTxn";
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .txnId(futureTxnId)
                                .via(scheduleCreateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord(scheduleCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleCreate - invalid transaction duration fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleCreateInvalidDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .validDurationSecs(0L)
                                .via(scheduleCreateTxn)
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord(scheduleCreateTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleCreate - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleCreateDuplicateFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .via("firstScheduleCreateTxn"),
                        scheduleCreate(
                                        SCHEDULE + "2",
                                        cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .txnId("firstScheduleCreateTxn")
                                .via(scheduleCreateTxn)
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }
        }

        @Nested
        @DisplayName("ScheduleCreate Failures on Pre-Handle")
        class ScheduleCreateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("ScheduleCreate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> scheduleCreateInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "schedule-create-txn-inner-id";
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(SENDER, "4")),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedScheduleCreateNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("ScheduleCreate Failures on Handle")
        class ScheduleCreateFailuresOnHandle {

            @HapiTest
            @DisplayName("ScheduleCreate - missing admin key signature fails on handle - full fee charged")
            final Stream<DynamicTest> scheduleCreateMissingAdminKeySignatureFailsOnHandle() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // missing admin key signature
                                .via(scheduleCreateTxn)
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                scheduleCreateTxn,
                                txnSize -> expectedScheduleCreateFullFeeUsd(Map.of(
                                        SIGNATURES, 1L,
                                        KEYS, 0L,
                                        PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(scheduleCreateTxn, PAYER));
            }

            @Nested
            @DisplayName("ScheduleCreate Duplicate on Handle")
            class ScheduleCreateDuplicateOnHandle {
                private static final String DUPLICATE_TXN_ID = "duplicateScheduleCreateTxnId";

                @Tag(ONLY_SUBPROCESS)
                @LeakyHapiTest
                @DisplayName("ScheduleCreate - duplicate on handle charges full fee to payer")
                final Stream<DynamicTest> scheduleCreateDuplicateFailsOnHandle() {
                    return hapiTest(
                            cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                            cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(SENDER, "4")),
                            usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                            scheduleCreate(
                                            SCHEDULE,
                                            cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                    .payingWith(PAYER)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("4")
                                    .via(DUPLICATE_TXN_ID),
                            scheduleCreate(
                                            SCHEDULE + "2",
                                            cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                    .payingWith(PAYER)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("3")
                                    .hasPrecheck(DUPLICATE_TRANSACTION),
                            validateChargedUsdWithinWithTxnSize(
                                    DUPLICATE_TXN_ID,
                                    txnSize -> expectedScheduleCreateFullFeeUsd(Map.of(
                                            SIGNATURES, 1L,
                                            KEYS, 0L,
                                            PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateChargedAccount(DUPLICATE_TXN_ID, PAYER));
                }
            }
        }
    }

    @Nested
    @DisplayName("ScheduleSign Simple Fees Positive Test Cases")
    class ScheduleSignPositiveTestCases {

        @HapiTest
        @DisplayName("ScheduleSign - base fee, single sig")
        final Stream<DynamicTest> scheduleSignBaseFee() {
            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .payingWith(PAYER),
                    scheduleSign(SCHEDULE).payingWith(SENDER).signedBy(SENDER).via(scheduleSignTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleSignTxn,
                            txnSize -> expectedScheduleSignFullFeeUsd(
                                    Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleSignTxn, SENDER));
        }

        @HapiTest
        @DisplayName("ScheduleSign - large payer key charges extra signatures and processing bytes")
        final Stream<DynamicTest> scheduleSignLargePayerKeyExtraFee() {
            KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = largeKeyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(SENDER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .payingWith(PAYER),
                    scheduleSign(SCHEDULE)
                            .payingWith(SENDER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(SENDER)
                            .via(scheduleSignTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleSignTxn,
                            txnSize -> expectedScheduleSignFullFeeUsd(
                                    Map.of(SIGNATURES, 20L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleSignTxn, SENDER));
        }
    }

    @Nested
    @DisplayName("ScheduleSign Simple Fees Negative Test Cases")
    class ScheduleSignNegativeTestCases {

        @Nested
        @DisplayName("ScheduleSign Failures on Ingest")
        class ScheduleSignFailuresOnIngest {

            @HapiTest
            @DisplayName("ScheduleSign - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleSignInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER),
                        scheduleSign(SCHEDULE)
                                .payingWith(SENDER)
                                .fee(1L)
                                .via(scheduleSignTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(scheduleSignTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleSign - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleSignInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_PAYER).balance(0L),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER),
                        scheduleSign(SCHEDULE)
                                .payingWith(NEW_PAYER)
                                .via(scheduleSignTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(scheduleSignTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleSign - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleSignExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredScheduleSignTxn";
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        scheduleSign(SCHEDULE)
                                .payingWith(PAYER)
                                .txnId(expiredTxnId)
                                .via(scheduleSignTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(scheduleSignTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("ScheduleSign Failures on Pre-Handle")
        class ScheduleSignFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("ScheduleSign - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> scheduleSignInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "schedule-sign-txn-inner-id";
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(NEW_PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(SENDER, "4")),
                        scheduleSign(SCHEDULE)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedScheduleSignNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("ScheduleSign Failures on Handle")
        class ScheduleSignFailuresOnHandle {

            @HapiTest
            @DisplayName("ScheduleSign - invalid schedule fails on handle - full fee charged")
            final Stream<DynamicTest> scheduleSignInvalidScheduleFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleSign("0.0.99999999")
                                .payingWith(PAYER)
                                .via(scheduleSignTxn)
                                .hasKnownStatus(INVALID_SCHEDULE_ID),
                        validateChargedUsdWithinWithTxnSize(
                                scheduleSignTxn,
                                txnSize -> expectedScheduleSignFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(scheduleSignTxn, PAYER));
            }
        }
    }

    @Nested
    @DisplayName("ScheduleDelete Simple Fees Positive Test Cases")
    class ScheduleDeletePositiveTestCases {

        @HapiTest
        @DisplayName("ScheduleDelete - base fee")
        final Stream<DynamicTest> scheduleDeleteBaseFee() {
            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER),
                    scheduleDelete(SCHEDULE)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(scheduleDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleDeleteTxn,
                            txnSize -> expectedScheduleDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleDeleteTxn, PAYER));
        }

        @HapiTest
        @DisplayName("ScheduleDelete - large payer key charges extra signatures and processing bytes")
        final Stream<DynamicTest> scheduleDeleteLargePayerKeyExtraFee() {
            KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = largeKeyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));

            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(NEW_PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .adminKey(ADMIN_KEY)
                            .payingWith(NEW_PAYER),
                    scheduleDelete(SCHEDULE)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER, ADMIN_KEY)
                            .via(scheduleDeleteTxn),
                    validateChargedUsdWithinWithTxnSize(
                            scheduleDeleteTxn,
                            txnSize -> expectedScheduleDeleteFullFeeUsd(
                                    Map.of(SIGNATURES, 21L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount(scheduleDeleteTxn, PAYER));
        }
    }

    @Nested
    @DisplayName("ScheduleDelete Simple Fees Negative Test Cases")
    class ScheduleDeleteNegativeTestCases {

        @Nested
        @DisplayName("ScheduleDelete Failures on Ingest")
        class ScheduleDeleteFailuresOnIngest {

            @HapiTest
            @DisplayName("ScheduleDelete - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleDeleteInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER),
                        scheduleDelete(SCHEDULE)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L)
                                .via(scheduleDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord(scheduleDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleDelete - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleDeleteInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_PAYER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(0L),
                        newKeyNamed(ADMIN_KEY),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .adminKey(ADMIN_KEY)
                                .payingWith(NEW_PAYER),
                        scheduleDelete(SCHEDULE)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via(scheduleDeleteTxn)
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord(scheduleDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ScheduleDelete - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> scheduleDeleteExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredScheduleDeleteTxn";
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        scheduleDelete(SCHEDULE)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .txnId(expiredTxnId)
                                .via(scheduleDeleteTxn)
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord(scheduleDeleteTxn).hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("ScheduleDelete Failures on Pre-Handle")
        class ScheduleDeleteFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("ScheduleDelete - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> scheduleDeleteInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "schedule-delete-txn-inner-id";
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(NEW_PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .adminKey(ADMIN_KEY)
                                .payingWith(NEW_PAYER),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(NEW_PAYER, "4")),
                        scheduleDelete(SCHEDULE)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedScheduleDeleteNetworkFeeOnlyUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("ScheduleDelete Failures on Handle")
        class ScheduleDeleteFailuresOnHandle {

            @HapiTest
            @DisplayName("ScheduleDelete - invalid schedule fails on handle - full fee charged")
            final Stream<DynamicTest> scheduleDeleteInvalidScheduleFails() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleDelete("0.0.99999999")
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(scheduleDeleteTxn)
                                .hasKnownStatus(INVALID_SCHEDULE_ID),
                        validateChargedUsdWithinWithTxnSize(
                                scheduleDeleteTxn,
                                txnSize -> expectedScheduleDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(scheduleDeleteTxn, PAYER));
            }

            @HapiTest
            @DisplayName("ScheduleDelete - immutable schedule (no admin key) fails on handle - full fee charged")
            final Stream<DynamicTest> scheduleDeleteImmutableScheduleFails() {
                return hapiTest(
                        cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                .payingWith(PAYER), // no admin key = immutable
                        scheduleDelete(SCHEDULE)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via(scheduleDeleteTxn)
                                .hasKnownStatus(SCHEDULE_IS_IMMUTABLE),
                        validateChargedUsdWithinWithTxnSize(
                                scheduleDeleteTxn,
                                txnSize -> expectedScheduleDeleteFullFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(scheduleDeleteTxn, PAYER));
            }

            @Nested
            @DisplayName("ScheduleDelete Duplicate on Handle")
            class ScheduleDeleteDuplicateOnHandle {
                private static final String DUPLICATE_TXN_ID = "duplicateScheduleDeleteTxnId";

                @Tag(ONLY_SUBPROCESS)
                @LeakyHapiTest
                @DisplayName("ScheduleDelete - duplicate on handle charges full fee to payer")
                final Stream<DynamicTest> scheduleDeleteDuplicateFailsOnHandle() {
                    return hapiTest(
                            cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                            cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                            cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                            cryptoCreate(NEW_PAYER).balance(ONE_HUNDRED_HBARS),
                            newKeyNamed(ADMIN_KEY),
                            scheduleCreate(
                                            SCHEDULE,
                                            cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                                    .adminKey(ADMIN_KEY)
                                    .payingWith(NEW_PAYER),
                            cryptoTransfer(movingHbar(ONE_HBAR).between(NEW_PAYER, "4")),
                            usableTxnIdNamed(DUPLICATE_TXN_ID).payerId(PAYER),
                            scheduleDelete(SCHEDULE)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, ADMIN_KEY)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("4")
                                    .via(DUPLICATE_TXN_ID),
                            scheduleDelete(SCHEDULE)
                                    .payingWith(PAYER)
                                    .signedBy(PAYER, ADMIN_KEY)
                                    .txnId(DUPLICATE_TXN_ID)
                                    .setNode("3")
                                    .hasPrecheck(DUPLICATE_TRANSACTION),
                            validateChargedUsdWithinWithTxnSize(
                                    DUPLICATE_TXN_ID,
                                    txnSize -> expectedScheduleDeleteFullFeeUsd(
                                            Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                    0.1),
                            validateChargedAccount(DUPLICATE_TXN_ID, PAYER));
                }
            }
        }
    }

    @Nested
    @DisplayName("ScheduleGetInfo Simple Fees Test Cases")
    class ScheduleGetInfoTestCases {

        @HapiTest
        @DisplayName("ScheduleGetInfo - base query fee")
        final Stream<DynamicTest> scheduleGetInfoBaseFee() {
            return hapiTest(
                    cryptoCreate(SENDER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(RECEIVER).balance(ONE_HUNDRED_HBARS),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    scheduleCreate(SCHEDULE, cryptoTransfer(movingHbar(1L).between(SENDER, RECEIVER)))
                            .payingWith(PAYER),
                    getScheduleInfo(SCHEDULE).payingWith(PAYER).via("scheduleGetInfoQuery"),
                    validateChargedUsdForQueries("scheduleGetInfoQuery", expectedScheduleGetInfoQueryFeeUsd(), 0.1),
                    validateNodePaymentAmountForQuery(
                            "scheduleGetInfoQuery", expectedScheduleGetInfoNodePaymentTinycents()));
        }

        @HapiTest
        @DisplayName("ScheduleGetInfo - invalid schedule fails - no fee charged")
        final Stream<DynamicTest> scheduleGetInfoInvalidScheduleFails() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    getScheduleInfo("0.0.99999999").payingWith(PAYER).hasCostAnswerPrecheck(INVALID_SCHEDULE_ID));
        }
    }
}
