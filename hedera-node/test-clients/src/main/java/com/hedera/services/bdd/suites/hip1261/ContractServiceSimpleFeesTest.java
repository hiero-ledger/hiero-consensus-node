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
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.accountAllowanceHook;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.movingHbar;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedAccount;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedContractCreateSimpleFeesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedContractDeleteSimpleFeesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedContractUpdateSimpleFeesUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.expectedNetworkOnlyFeeUsd;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.getChargedGasForContractCall;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.getChargedGasForContractCreate;
import static com.hedera.services.bdd.suites.hip1261.utils.FeesChargingUtils.validateChargedUsdWithinWithTxnSize;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_PAYER_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_DURATION;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_START;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static org.hiero.hapi.support.fees.Extra.HOOK_UPDATES;
import static org.hiero.hapi.support.fees.Extra.KEYS;
import static org.hiero.hapi.support.fees.Extra.PROCESSING_BYTES;
import static org.hiero.hapi.support.fees.Extra.SIGNATURES;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyEmbeddedHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class ContractServiceSimpleFeesTest {
    private static final String PAYER = "payer";
    private static final String PAYER_KEY = "payerKey";
    private static final String ADMIN_KEY = "adminKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String CONTRACT = "EmptyOne";
    private static final String CALL_CONTRACT = "SmartContractsFees";
    private static final String HOOK_CONTRACT = "TruePreHook";

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "hooks.hooksEnabled", "true"));
    }

    @Nested
    @DisplayName("ContractCreate Simple Fees Positive Test Cases")
    class ContractCreatePositiveTestCases {

        @HapiTest
        @DisplayName("ContractCreate with admin key - extra signatures charged")
        final Stream<DynamicTest> contractCreateWithAdminKeyFeeCharged() {
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L)
                            .via("createTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"))),
                    validateChargedUsdWithinWithTxnSize(
                            "createTxn",
                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                            SIGNATURES, 2L,
                                            KEYS, 1L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + gasUsedRef.get(),
                            0.01),
                    validateChargedAccount("createTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ContractCreate with threshold admin key - extra signatures and one extra key charged")
        final Stream<DynamicTest> contractCreateWithThresholdAdminKeyExtraKeyFeeCharged() {
            final KeyShape twoKeyAdminShape = threshOf(1, SIMPLE, SIMPLE);
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY).shape(twoKeyAdminShape),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L)
                            .via("createTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"))),
                    validateChargedUsdWithinWithTxnSize(
                            "createTxn",
                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                            SIGNATURES, 3L,
                                            KEYS, 2L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + gasUsedRef.get(),
                            0.01),
                    validateChargedAccount("createTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ContractCreate with threshold admin key - extra signatures and two extra key charged")
        final Stream<DynamicTest> contractCreateWithThresholdAdminKeyTwoExtraKeysFeeCharged() {
            final KeyShape threeKeyAdminShape = threshOf(1, SIMPLE, SIMPLE, SIMPLE);
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY).shape(threeKeyAdminShape),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L)
                            .via("createTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"))),
                    validateChargedUsdWithinWithTxnSize(
                            "createTxn",
                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                            SIGNATURES, 4L,
                                            KEYS, 3L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + gasUsedRef.get(),
                            0.01),
                    validateChargedAccount("createTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ContractCreate with large payer key - extra signatures and processing bytes charged")
        final Stream<DynamicTest> contractCreateLargePayerKeyFeeCharged() {
            KeyShape largeKeyShape = threshOf(
                    1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE,
                    SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            SigControl allSigning = largeKeyShape.signedWith(
                    sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L)
                            .via("createTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"))),
                    validateChargedUsdWithinWithTxnSize(
                            "createTxn",
                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                            SIGNATURES, 21L,
                                            KEYS, 1L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + gasUsedRef.get(),
                            0.01),
                    validateChargedAccount("createTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ContractCreate with hook update - extra hook charge")
        final Stream<DynamicTest> contractCreateWithHookFeeCharged() {
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L)
                            .via("createTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"))),
                    validateChargedUsdWithinWithTxnSize(
                            "createTxn",
                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                            SIGNATURES, 2L,
                                            KEYS, 1L,
                                            HOOK_UPDATES, 1L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + gasUsedRef.get(),
                            0.01),
                    validateChargedAccount("createTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ContractCreate with two hook updates - two extra hooks charged")
        final Stream<DynamicTest> contractCreateWithTwoHooksFeeCharged() {
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    newKeyNamed(ADMIN_KEY),
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    uploadInitCode(HOOK_CONTRACT),
                    contractCreate(HOOK_CONTRACT).gas(5_000_000L),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .withHooks(accountAllowanceHook(1L, HOOK_CONTRACT), accountAllowanceHook(2L, HOOK_CONTRACT))
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L)
                            .via("createTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"))),
                    validateChargedUsdWithinWithTxnSize(
                            "createTxn",
                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                            SIGNATURES, 2L,
                                            HOOK_UPDATES, 2L,
                                            PROCESSING_BYTES, (long) txnSize))
                                    + gasUsedRef.get(),
                            0.01),
                    validateChargedAccount("createTxn", PAYER));
        }
    }

    @Nested
    @DisplayName("ContractCreate Simple Fees Negative Test Cases")
    class ContractCreateNegativeTestCases {

        @Nested
        @DisplayName("ContractCreate Failures on Ingest")
        class ContractCreateFailuresOnIngest {

            @HapiTest
            @DisplayName("ContractCreate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCreateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .fee(1L)
                                .via("createTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ContractCreate - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCreateInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(0L),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .via("createTxn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ContractCreate - memo too long fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCreateMemoTooLongFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .memo("x".repeat(101))
                                .via("createTxn")
                                .hasPrecheck(MEMO_TOO_LONG),
                        getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ContractCreate - expired transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCreateExpiredFailsOnIngest() {
                final var expiredTxnId = "expiredContractCreateTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        usableTxnIdNamed(expiredTxnId).modifyValidStart(-3_600L).payerId(PAYER),
                        contractCreate(CONTRACT)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .txnId(expiredTxnId)
                                .via("createTxn")
                                .hasPrecheck(TRANSACTION_EXPIRED),
                        getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ContractCreate - too far in future fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCreateTooFarInFutureFailsOnIngest() {
                final var futureTxnId = "futureContractCreateTxn";
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        usableTxnIdNamed(futureTxnId).modifyValidStart(3_600L).payerId(PAYER),
                        contractCreate(CONTRACT)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .txnId(futureTxnId)
                                .via("createTxn")
                                .hasPrecheck(INVALID_TRANSACTION_START),
                        getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ContractCreate - invalid duration fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCreateInvalidDurationFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .validDurationSecs(0L)
                                .via("createTxn")
                                .hasPrecheck(INVALID_TRANSACTION_DURATION),
                        getTxnRecord("createTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("ContractCreate Failures on Pre-Handle")
        class ContractCreateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("ContractCreate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> contractCreateInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "contract-create-inner-id";
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, "4")),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER)
                                .gas(200_000L)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("ContractCreate Failures on Handle")
        class ContractCreateFailuresOnHandle {

            @HapiTest
            @DisplayName("ContractCreate - missing admin key signature fails on handle - full fee charged")
            final Stream<DynamicTest> contractCreateMissingAdminKeySignatureFailsOnHandle() {
                final var gasUsedRef = new AtomicReference<>(0.0);
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER) // missing ADMIN_KEY sig
                                .gas(200_000L)
                                .via("createTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        withOpContext((spec, op) -> {
                            gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"));
                            allRunFor(
                                    spec,
                                    validateChargedUsdWithinWithTxnSize(
                                            "createTxn",
                                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                                            SIGNATURES, 1L,
                                                            KEYS, 1L,
                                                            PROCESSING_BYTES, (long) txnSize))
                                                    + gasUsedRef.get(),
                                            0.01));
                        }),
                        validateChargedAccount("createTxn", PAYER));
            }

            @HapiTest
            @DisplayName("ContractCreate - invalid admin key signature fails on handle - full fee charged")
            final Stream<DynamicTest> contractCreateInvalidAdminKeySignatureFailsOnHandle() {
                final var gasUsedRef = new AtomicReference<>(0.0);
                KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));

                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY).shape(keyShape),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .sigControl(forKey(ADMIN_KEY, invalidSig))
                                .signedBy(PAYER)
                                .gas(200_000L)
                                .via("createTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        withOpContext((spec, op) -> {
                            gasUsedRef.set(getChargedGasForContractCreate(spec, "createTxn"));
                            allRunFor(
                                    spec,
                                    validateChargedUsdWithinWithTxnSize(
                                            "createTxn",
                                            txnSize -> expectedContractCreateSimpleFeesUsd(Map.of(
                                                            SIGNATURES, 2L,
                                                            KEYS, 2L,
                                                            PROCESSING_BYTES, (long) txnSize))
                                                    + gasUsedRef.get(),
                                            0.01));
                        }),
                        validateChargedAccount("createTxn", PAYER));
            }
        }
    }

    @Nested
    @DisplayName("ContractCall Simple Fees Positive Test Cases")
    class ContractCallPositiveTestCases {

        @HapiTest
        @DisplayName("ContractCall - base fee + gas charged")
        final Stream<DynamicTest> contractCallBaseFeeCharged() {
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    uploadInitCode(CALL_CONTRACT),
                    contractCreate(CALL_CONTRACT).gas(200_000L),
                    contractCall(CALL_CONTRACT, "contractCall1Byte", (Object) new byte[] {0})
                            .payingWith(PAYER)
                            .gas(100_000L)
                            .via("callTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCall(spec, "callTxn"))),
                    withOpContext(
                            (spec, op) -> allRunFor(spec, validateChargedUsdWithin("callTxn", gasUsedRef.get(), 0.1))),
                    validateChargedAccount("callTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ContractCall - large payer key extra signatures + gas charged")
        final Stream<DynamicTest> contractCallLargePayerKeyFeeCharged() {
            final KeyShape largeKeyShape =
                    threshOf(1, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE, SIMPLE);
            final SigControl allSigning = largeKeyShape.signedWith(sigs(ON, ON, ON, ON, ON, ON, ON, ON, ON, ON));
            final var gasUsedRef = new AtomicReference<>(0.0);
            return hapiTest(
                    newKeyNamed(PAYER_KEY).shape(largeKeyShape),
                    cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                    uploadInitCode(CALL_CONTRACT),
                    contractCreate(CALL_CONTRACT).gas(200_000L),
                    contractCall(CALL_CONTRACT, "contractCall1Byte", (Object) new byte[] {0})
                            .payingWith(PAYER)
                            .sigControl(forKey(PAYER_KEY, allSigning))
                            .signedBy(PAYER)
                            .gas(100_000L)
                            .via("callTxn"),
                    withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCall(spec, "callTxn"))),
                    withOpContext(
                            (spec, op) -> allRunFor(spec, validateChargedUsdWithin("callTxn", gasUsedRef.get(), 0.1))),
                    validateChargedAccount("callTxn", PAYER));
        }
    }

    @Nested
    @DisplayName("ContractCall Simple Fees Negative Test Cases")
    class ContractCallNegativeTestCases {

        @Nested
        @DisplayName("ContractCall Failures on Ingest")
        class ContractCallFailuresOnIngest {

            @HapiTest
            @DisplayName("ContractCall - insufficient payer balance fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCallInsufficientPayerBalanceFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(0L),
                        uploadInitCode(CALL_CONTRACT),
                        contractCreate(CALL_CONTRACT).gas(200_000L),
                        contractCall(CALL_CONTRACT, "contractCall1Byte", (Object) new byte[] {0})
                                .payingWith(PAYER)
                                .gas(100_000L)
                                .via("callTxn")
                                .hasPrecheck(INSUFFICIENT_PAYER_BALANCE),
                        getTxnRecord("callTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }

            @HapiTest
            @DisplayName("ContractCall - duplicate transaction fails on ingest - no fee charged")
            final Stream<DynamicTest> contractCallDuplicateFailsOnIngest() {
                return hapiTest(
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CALL_CONTRACT),
                        contractCreate(CALL_CONTRACT).gas(200_000L),
                        contractCall(CALL_CONTRACT, "contractCall1Byte", (Object) new byte[] {0})
                                .payingWith(PAYER)
                                .gas(100_000L)
                                .via("firstCallTxn"),
                        contractCall(CALL_CONTRACT, "contractCall1Byte", (Object) new byte[] {0})
                                .payingWith(PAYER)
                                .gas(100_000L)
                                .txnId("firstCallTxn")
                                .via("callTxn")
                                .hasPrecheck(DUPLICATE_TRANSACTION));
            }
        }

        @Nested
        @DisplayName("ContractCall Failures on Pre-Handle")
        class ContractCallFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("ContractCall - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> contractCallInvalidPayerSigFailsOnPreHandle() {
                final var gasUsedRef = new AtomicReference<>(0.0);
                final String INNER_ID = "contract-call-inner-id";
                final KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                final SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT).gas(200_000L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, "4")),
                        contractCall(CONTRACT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .gas(100_000L)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        withOpContext((spec, op) -> gasUsedRef.set(getChargedGasForContractCall(spec, INNER_ID))),
                        withOpContext((spec, op) ->
                                allRunFor(spec, validateChargedUsdWithin(INNER_ID, gasUsedRef.get(), 0.1))));
            }
        }
    }

    @Nested
    @DisplayName("ContractUpdate Simple Fees Positive Test Cases")
    class ContractUpdatePositiveTestCases {

        @HapiTest
        @DisplayName("ContractUpdate - base fee charged")
        final Stream<DynamicTest> contractUpdateBaseFeeCharged() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L),
                    contractUpdate(CONTRACT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via("updateTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "updateTxn",
                            txnSize -> expectedContractUpdateSimpleFeesUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount("updateTxn", PAYER));
        }

        @HapiTest
        @DisplayName("ContractUpdate - new admin key with multiple signatures charged")
        final Stream<DynamicTest> contractUpdateNewAdminKeyMultipleSignaturesFeeCharged() {
            final KeyShape twoKeyShape = threshOf(1, SIMPLE, SIMPLE);
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    newKeyNamed(NEW_ADMIN_KEY).shape(twoKeyShape),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L),
                    contractUpdate(CONTRACT)
                            .newKey(NEW_ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                            .via("updateTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "updateTxn",
                            txnSize -> expectedContractUpdateSimpleFeesUsd(Map.of(
                                    SIGNATURES, 4L,
                                    KEYS, 2L,
                                    PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount("updateTxn", PAYER));
        }
    }

    @Nested
    @DisplayName("ContractUpdate Simple Fees Negative Test Cases")
    class ContractUpdateNegativeTestCases {

        @Nested
        @DisplayName("ContractUpdate Failures on Ingest")
        class ContractUpdateFailuresOnIngest {

            @HapiTest
            @DisplayName("ContractUpdate - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> contractUpdateInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(ADMIN_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .signedBy(PAYER, ADMIN_KEY),
                        contractUpdate(CONTRACT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L)
                                .via("updateTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("updateTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("ContractUpdate Failures on Pre-Handle")
        class ContractUpdateFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("ContractUpdate - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> contractUpdateInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "contract-update-inner-id";
                final KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                final SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .gas(200_000L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, "4")),
                        contractUpdate(CONTRACT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("ContractUpdate Failures on Handle")
        class ContractUpdateFailuresOnHandle {

            @HapiTest
            @DisplayName("ContractUpdate - missing required signature fails on handle - full fee charged")
            final Stream<DynamicTest> contractUpdateMissingSignatureFailsOnHandle() {
                return hapiTest(
                        newKeyNamed(ADMIN_KEY),
                        newKeyNamed(NEW_ADMIN_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .signedBy(PAYER, ADMIN_KEY),
                        contractUpdate(CONTRACT)
                                .newKey(NEW_ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .via("updateTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                "updateTxn",
                                txnSize -> expectedContractUpdateSimpleFeesUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.01),
                        validateChargedAccount("updateTxn", PAYER));
            }
        }
    }

    @Nested
    @DisplayName("ContractDelete Simple Fees Positive Test Cases")
    class ContractDeletePositiveTestCases {

        @HapiTest
        @DisplayName("ContractDelete - base fee charged with 2 signatures")
        final Stream<DynamicTest> contractDeleteBaseFeeCharged() {
            return hapiTest(
                    cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                    newKeyNamed(ADMIN_KEY),
                    uploadInitCode(CONTRACT),
                    contractCreate(CONTRACT)
                            .adminKey(ADMIN_KEY)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .gas(200_000L),
                    contractDelete(CONTRACT)
                            .payingWith(PAYER)
                            .signedBy(PAYER, ADMIN_KEY)
                            .via("deleteTxn"),
                    validateChargedUsdWithinWithTxnSize(
                            "deleteTxn",
                            txnSize -> expectedContractDeleteSimpleFeesUsd(
                                    Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                            0.1),
                    validateChargedAccount("deleteTxn", PAYER));
        }
    }

    @Nested
    @DisplayName("ContractDelete Simple Fees Negative Test Cases")
    class ContractDeleteNegativeTestCases {

        @Nested
        @DisplayName("ContractDelete Failures on Ingest")
        class ContractDeleteFailuresOnIngest {

            @HapiTest
            @DisplayName("ContractDelete - insufficient tx fee fails on ingest - no fee charged")
            final Stream<DynamicTest> contractDeleteInsufficientTxFeeFailsOnIngest() {
                return hapiTest(
                        newKeyNamed(ADMIN_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .signedBy(PAYER, ADMIN_KEY),
                        contractDelete(CONTRACT)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .fee(1L)
                                .via("deleteTxn")
                                .hasPrecheck(INSUFFICIENT_TX_FEE),
                        getTxnRecord("deleteTxn").hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND));
            }
        }

        @Nested
        @DisplayName("ContractDelete Failures on Pre-Handle")
        class ContractDeleteFailuresOnPreHandle {

            @LeakyEmbeddedHapiTest(reason = MUST_SKIP_INGEST)
            @DisplayName("ContractDelete - invalid payer signature fails on pre-handle - network fee only")
            final Stream<DynamicTest> contractDeleteInvalidPayerSigFailsOnPreHandle() {
                final String INNER_ID = "contract-delete-inner-id";
                final KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE);
                final SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF));
                return hapiTest(
                        newKeyNamed(PAYER_KEY).shape(keyShape),
                        cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                        newKeyNamed(ADMIN_KEY),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .signedBy(PAYER, ADMIN_KEY)
                                .gas(200_000L),
                        cryptoTransfer(movingHbar(ONE_HBAR).between(PAYER, "4")),
                        contractDelete(CONTRACT)
                                .payingWith(PAYER)
                                .sigControl(forKey(PAYER_KEY, invalidSig))
                                .signedBy(PAYER, ADMIN_KEY)
                                .setNode("4")
                                .via(INNER_ID)
                                .hasKnownStatus(INVALID_PAYER_SIGNATURE),
                        getTxnRecord(INNER_ID).assertingNothingAboutHashes().logged(),
                        validateChargedUsdWithinWithTxnSize(
                                INNER_ID,
                                txnSize -> expectedNetworkOnlyFeeUsd(
                                        Map.of(SIGNATURES, 2L, PROCESSING_BYTES, (long) txnSize)),
                                0.1),
                        validateChargedAccount(INNER_ID, "4"));
            }
        }

        @Nested
        @DisplayName("ContractDelete Failures on Handle")
        class ContractDeleteFailuresOnHandle {

            @HapiTest
            @DisplayName("ContractDelete - missing required signature fails on handle - full fee charged")
            final Stream<DynamicTest> contractDeleteMissingSignatureFailsOnHandle() {
                return hapiTest(
                        newKeyNamed(ADMIN_KEY),
                        cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                        uploadInitCode(CONTRACT),
                        contractCreate(CONTRACT)
                                .adminKey(ADMIN_KEY)
                                .payingWith(PAYER)
                                .gas(200_000L)
                                .signedBy(PAYER, ADMIN_KEY),
                        contractDelete(CONTRACT)
                                .payingWith(PAYER)
                                .signedBy(PAYER)
                                .via("deleteTxn")
                                .hasKnownStatus(INVALID_SIGNATURE),
                        validateChargedUsdWithinWithTxnSize(
                                "deleteTxn",
                                txnSize -> expectedContractDeleteSimpleFeesUsd(
                                        Map.of(SIGNATURES, 1L, PROCESSING_BYTES, (long) txnSize)),
                                0.01),
                        validateChargedAccount("deleteTxn", PAYER));
            }
        }
    }
}
