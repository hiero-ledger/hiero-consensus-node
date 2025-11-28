// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.keys.ControlForKey.forKey;
import static com.hedera.services.bdd.spec.keys.KeyShape.SIMPLE;
import static com.hedera.services.bdd.spec.keys.KeyShape.listOf;
import static com.hedera.services.bdd.spec.keys.KeyShape.sigs;
import static com.hedera.services.bdd.spec.keys.KeyShape.threshOf;
import static com.hedera.services.bdd.spec.keys.SigControl.OFF;
import static com.hedera.services.bdd.spec.keys.SigControl.ON;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyListNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsdWithin;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.INVALID_TXN_AT_INGEST_ZERO_PAYER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.SUCCESS_TXN_FULL_CHARGE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesJsonLoader.fromClassPath;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.overrideSimpleFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.restoreSimpleFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.snapshotSimpleFees;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.keys.KeyShape;
import com.hedera.services.bdd.spec.keys.SigControl;
import com.hedera.services.bdd.suites.hip1261.utils.JsonToFeeScheduleConverter;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesParams;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesReferenceTestCalculator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class SimpleFeesCryptoCreateTest {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String PAYER_KEY = "payerKey";

    private static final String ORIGINAL_SIMPLE_FEES_REGISTRY_KEY = "originalSimpleFees";
    private static final String CUSTOM_SIMPLE_FEES_JSON_PATH = "/hip1261/customSimpleFees.json";

    /**
     * Expected charges for CryptoCreate with custom schedule without any extras
     * | Scenario                         | Sigs  | Keys | PayerCharged  | payerCharged USD |
     * |----------------------------------|-------|------|---------------|------------------|
     * | PAYER only, one signature        |     1 |    0 |     500000000 |     0.0500000000 |
     */
    @HapiTest
    @DisplayName("Crypto Create - full charging without extras")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithoutExtras() {

        return hapiTest(
                // perform the transaction under test with the system default fees
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("testAccount")
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn")
                        .logged(),

                // save the current active schedule to registry
                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with the custom schedule
                assertFees(
                        "createAccountTxn",
                        CRYPTO_CREATE,
                        SimpleFeesParams.create().signatures(1L),
                        CUSTOM_SIMPLE_FEES_JSON_PATH,
                        SUCCESS_TXN_FULL_CHARGE),
                restoreSimpleFees("originalSimpleFees"));
    }

    //    @HapiTest
    //    @DisplayName("Crypto Create - full charging with one extra signature")
    //    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithOneExtraSignature() {
    //
    //        return hapiTest(
    //                // save the current active schedule to registry
    //                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),
    //
    //                // override the active schedule with custom schedule for this test
    //                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),
    //
    //                // perform the transaction under test
    //                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
    //                newKeyNamed(ADMIN_KEY),
    //                cryptoCreate("testAccount")
    //                        .payingWith(PAYER)
    //                        .signedBy(PAYER, ADMIN_KEY)
    //                        .fee(ONE_HBAR)
    //                        .via("createAccountTxn"),
    //
    //                // validate charged fees
    //                assertFees(
    //                        "createAccountTxn",
    //                        CRYPTO_CREATE,
    //                        SimpleFeesParams.create().signatures(2L),
    //                        CUSTOM_SIMPLE_FEES_JSON_PATH,
    //                        SUCCESS_TXN_FULL_CHARGE),
    //                restoreSimpleFees("originalSimpleFees"));
    //    }
    //
    //    @HapiTest
    //    @DisplayName("Crypto Create - full charging with two extra signatures")
    //    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithTwoExtraSignatures() {
    //
    //        return hapiTest(
    //                // save the current active schedule to registry
    //                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),
    //
    //                // override the active schedule with custom schedule for this test
    //                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),
    //
    //                // perform the transaction under test
    //                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
    //                newKeyNamed(ADMIN_KEY),
    //                newKeyNamed(SUBMIT_KEY),
    //                cryptoCreate("testAccount")
    //                        .payingWith(PAYER)
    //                        .signedBy(PAYER, ADMIN_KEY, SUBMIT_KEY)
    //                        .fee(ONE_HBAR)
    //                        .via("createAccountTxn"),
    //
    //                // validate charged fees
    //                assertFees(
    //                        "createAccountTxn",
    //                        CRYPTO_CREATE,
    //                        SimpleFeesParams.create().signatures(3L),
    //                        CUSTOM_SIMPLE_FEES_JSON_PATH,
    //                        SUCCESS_TXN_FULL_CHARGE),
    //                restoreSimpleFees("originalSimpleFees"));
    //    }

    /**
     * Expected charges for CryptoCreate with custom schedule with included key and extra signatures
     * | Scenario                         | Sigs  | Keys | PayerCharged | payerCharged USD |
     * |----------------------------------|-------|------|--------------|------------------|
     * | PAYER + 1 signature + 1 key      |     2 |    1 |    510000000 |     0.0510000000 |
     */
    @HapiTest
    @DisplayName("Crypto Create - full charging with included key and extra signatures")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithIncludedKeyAndExtraSignatures() {

        return hapiTest(
                // perform the transaction under test with the system default fees
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                cryptoCreate("testAccount")
                        .key(ADMIN_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn"),

                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with the custom schedule
                assertFees(
                        "createAccountTxn",
                        CRYPTO_CREATE,
                        SimpleFeesParams.create().signatures(2L).keys(1L),
                        CUSTOM_SIMPLE_FEES_JSON_PATH,
                        SUCCESS_TXN_FULL_CHARGE),
                restoreSimpleFees("originalSimpleFees"));
    }

    /**
     * Expected charges for CryptoCreate with custom schedule with extra key and extra signatures
     * | Scenario                         | Sigs  | Keys | PayerCharged | payerCharged USD |
     * |----------------------------------|-------|------|--------------|------------------|
     * | PAYER + 2 signatures + 2 keys    |     3 |    2 |    620000000 |     0.0620000000 |
     */
    @HapiTest
    @DisplayName("Crypto Create - full charging with extra key and extra signatures")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithExtraKeyAndExtraSignatures() {

        return hapiTest(
                // perform the transaction under test with the system default fees
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUBMIT_KEY),
                cryptoCreate("testAccount")
                        .key(ADMIN_KEY)
                        .key(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY, SUBMIT_KEY)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn"),

                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with the custom schedule
                assertFees(
                        "createAccountTxn",
                        CRYPTO_CREATE,
                        SimpleFeesParams.create().signatures(3L).keys(2L),
                        CUSTOM_SIMPLE_FEES_JSON_PATH,
                        SUCCESS_TXN_FULL_CHARGE),
                restoreSimpleFees("originalSimpleFees"));
    }

    /**
     * Expected charges for CryptoCreate with custom schedule with threshold key and extra signatures
     * | Scenario                         | Sigs  | Keys | PayerCharged | payerCharged USD |
     * |----------------------------------|-------|------|--------------|------------------|
     * | PAYER + 3 extra sig + 4 keys     |     4 |    4 |    830000000 |     0.0830000000 |
     */
    @HapiTest
    @DisplayName("Crypto Create - full charging with valid payer threshold key")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithPayerThresholdKey() {

        // Define a threshold submit key that requires two simple keys signatures
        KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        // Create a valid signature with both simple keys signing
        SigControl validSig = keyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));

        return hapiTest(
                // create threshold keys
                newKeyNamed(PAYER_KEY).shape(keyShape),

                // perform the transaction under test with the system default fees
                cryptoCreate(PAYER)
                        .key(PAYER_KEY)
                        .sigControl(forKey(PAYER_KEY, validSig))
                        .balance(ONE_HUNDRED_HBARS),
                cryptoCreate("testAccount")
                        .key(PAYER_KEY)
                        .sigControl(forKey(PAYER_KEY, validSig))
                        .payingWith(PAYER)
                        .signedBy(PAYER_KEY)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn"),

                // save the current active schedule to registry
                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with the custom schedule
                assertFees(
                        "createAccountTxn",
                        CRYPTO_CREATE,
                        SimpleFeesParams.create().signatures(4L).keys(4L),
                        CUSTOM_SIMPLE_FEES_JSON_PATH,
                        SUCCESS_TXN_FULL_CHARGE),
                restoreSimpleFees("originalSimpleFees"));
    }

    /**
     * Expected charges for CryptoCreate with custom schedule with threshold key and extra signatures
     * | Scenario                         | Sigs  | Keys | PayerCharged | payerCharged USD |
     * |----------------------------------|-------|------|--------------|------------------|
     * | PAYER + 1 extra sig + 2 keys     |     2 |    2 |    610000000 |     0.0610000000 |
     */
    @HapiTest
    @DisplayName("Crypto Create - full charging with valid payer key - full key list")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithPayerKeyList() {

        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // create keys list
                newKeyNamed("firstKey").shape(SIMPLE),
                newKeyNamed("secondKey").shape(SIMPLE),
                newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),

                // perform the transaction under test
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("testAccount")
                        .key(PAYER_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER_KEY)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn")
                        .logged(),

                // validate charged fees
                assertFees(
                        "createAccountTxn",
                        CRYPTO_CREATE,
                        SimpleFeesParams.create().signatures(2L).keys(2L),
                        CUSTOM_SIMPLE_FEES_JSON_PATH,
                        SUCCESS_TXN_FULL_CHARGE),
                restoreSimpleFees("originalSimpleFees"));
    }

    /**
     * Expected charges for CryptoCreate with custom schedule with threshold key and extra signatures
     * | Scenario                         | Sigs  | Keys | PayerCharged | payerCharged USD |
     * |----------------------------------|-------|------|--------------|------------------|
     * | PAYER + 3 extra sig + 4 keys     |     4 |    4 |    830000000 |     0.0830000000 |
     */
    @HapiTest
    @DisplayName("Crypto Create - full charging with valid admin threshold key")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithAdminThresholdKey() {

        // Define a threshold submit key that requires two simple keys signatures
        KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));

        // Create a valid signature with both simple keys signing
        SigControl validSig = keyShape.signedWith(sigs(ON, OFF, sigs(ON, ON)));

        return hapiTest(
                // create threshold keys
                newKeyNamed(ADMIN_KEY).shape(keyShape),

                // perform the transaction under test with the system default fees
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("testAccount")
                        .key(ADMIN_KEY)
                        .sigControl(forKey(ADMIN_KEY, validSig))
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn"),

                // save the current active schedule to registry
                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with the custom schedule
                assertFees(
                        "createAccountTxn",
                        CRYPTO_CREATE,
                        SimpleFeesParams.create().signatures(4L).keys(4L),
                        CUSTOM_SIMPLE_FEES_JSON_PATH,
                        SUCCESS_TXN_FULL_CHARGE),
                restoreSimpleFees("originalSimpleFees"));
    }

    /**
     * Expected charges for CryptoCreate with custom schedule with threshold key and extra signatures
     * | Scenario                         | Sigs  | Keys | PayerCharged | payerCharged USD |
     * |----------------------------------|-------|------|--------------|------------------|
     * | PAYER + 2 extra sig + 2 keys     |     3 |    2 |    620000000 |     0.0620000000 |
     */
    @HapiTest
    @DisplayName("Crypto Create - full charging with valid admin key - full key list")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithAdminKeyList() {

        return hapiTest(

                // create keys list
                newKeyNamed("firstKey").shape(SIMPLE),
                newKeyNamed("secondKey").shape(SIMPLE),
                newKeyListNamed(ADMIN_KEY, List.of("firstKey", "secondKey")),

                // perform the transaction under test with the system default fees
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("testAccount")
                        .key(ADMIN_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn"),

                // save the current active schedule to registry
                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with the custom schedule
                assertFees(
                        "createAccountTxn",
                        CRYPTO_CREATE,
                        SimpleFeesParams.create().signatures(3L).keys(2L),
                        CUSTOM_SIMPLE_FEES_JSON_PATH,
                        SUCCESS_TXN_FULL_CHARGE),
                restoreSimpleFees("originalSimpleFees"));
    }

    // Negative tests
    // why did we get charged here????
    @HapiTest
    @DisplayName("Crypto Create with partial payer key list results in failure and zero payer fees")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithPayerPartialKeyListFails() {

        return hapiTest(
                // create keys list
                newKeyNamed("firstKey").shape(SIMPLE),
                newKeyNamed("secondKey").shape(SIMPLE),
                newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),

                // perform the transaction under test with the system default fees
                cryptoCreate(PAYER).key(PAYER_KEY).balance(ONE_HUNDRED_HBARS),
                cryptoCreate("testAccount")
                        .key(PAYER_KEY)
                        .payingWith(PAYER)
                        .signedBy("firstKey")
                        .fee(ONE_HBAR)
                        .via("createAccountTxn")
                        .hasPrecheck(INVALID_SIGNATURE),

                // save the current active schedule to registry
                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with zero payer fees
                assertZeroPayerFees(CRYPTO_CREATE, CUSTOM_SIMPLE_FEES_JSON_PATH, INVALID_TXN_AT_INGEST_ZERO_PAYER),
                restoreSimpleFees("originalSimpleFees"));
    }

    //    @HapiTest
    //    @DisplayName("Crypto Create - full charging with valid admin key - partial key list")
    //    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithAdminPartialKeyList() {
    //
    //        return hapiTest(
    //                // save the current active schedule to registry
    //                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),
    //
    //                // override the active schedule with custom schedule for this test
    //                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),
    //
    //                // create keys list
    //                newKeyNamed("firstKey").shape(SIMPLE),
    //                newKeyNamed("secondKey").shape(SIMPLE),
    //                newKeyListNamed(ADMIN_KEY, List.of("firstKey", "secondKey")),
    //
    //                // perform the transaction under test
    //                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
    //                cryptoCreate("testAccount")
    //                        .payingWith(PAYER)
    //                        .signedBy(PAYER, "secondKey")
    //                        .fee(ONE_HBAR)
    //                        .via("createAccountTxn"),
    //
    //                // validate charged fees
    //                assertFees(
    //                        "createAccountTxn",
    //                        CRYPTO_CREATE,
    //                        SimpleFeesParams.create().signatures(4L).keys(4L),
    //                        CUSTOM_SIMPLE_FEES_JSON_PATH,
    //                        SUCCESS_TXN_FULL_CHARGE),
    //                restoreSimpleFees("originalSimpleFees"));
    //    }

    @HapiTest
    @DisplayName("Crypto Create with invalid payer threshold key results in failure and zero payer fees")
    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithInvalidThresholdKey() {
        final AtomicLong initialBalance = new AtomicLong();
        final AtomicLong afterBalance = new AtomicLong();
        // Define a threshold submit key that requires two simple keys signatures
        KeyShape keyShape = threshOf(2, SIMPLE, SIMPLE, listOf(2));
        // Create an invalid signature with only one simple key signing
        SigControl invalidSig = keyShape.signedWith(sigs(ON, OFF, sigs(OFF, OFF)));

        return hapiTest(
                newKeyNamed(PAYER_KEY).shape(keyShape),
                cryptoCreate(PAYER)
                        .key(PAYER_KEY)
                        .sigControl(forKey(PAYER_KEY, invalidSig))
                        .balance(ONE_HUNDRED_HBARS),

                // Save payer balance before
                getAccountBalance(PAYER).exposingBalanceTo(initialBalance::set),

                // perform the transaction under test with the system default fees
                cryptoCreate("testAccount")
                        .sigControl(forKey(PAYER_KEY, invalidSig))
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("createAccountTxn")
                        .hasPrecheck(INVALID_SIGNATURE),

                // assert no txn record is created
                getTxnRecord("createAccountTxn").logged().hasAnswerOnlyPrecheckFrom(RECORD_NOT_FOUND),

                // Save balances and assert changes
                getAccountBalance(PAYER).exposingBalanceTo(afterBalance::set),
                withOpContext((spec, log) -> {
                    assertEquals(initialBalance.get(), afterBalance.get());
                }),

                // save the current active schedule to registry
                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),

                // override the active schedule with custom schedule for this test
                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),

                // validate charged fees with zero payer fees
                assertZeroPayerFees(CRYPTO_CREATE, CUSTOM_SIMPLE_FEES_JSON_PATH, INVALID_TXN_AT_INGEST_ZERO_PAYER),
                restoreSimpleFees("originalSimpleFees"));
    }

    //    @HapiTest
    //    @DisplayName("Crypto Create - full charging with invalid payer key list")
    //    Stream<DynamicTest> cryptoCreateWithCustomScheduleWithInvalidPayerKeyList() {
    //
    //        return hapiTest(
    //                // save the current active schedule to registry
    //                snapshotSimpleFees(ORIGINAL_SIMPLE_FEES_REGISTRY_KEY),
    //
    //                // override the active schedule with custom schedule for this test
    //                useCustomSimpleFeesFromJson(CUSTOM_SIMPLE_FEES_JSON_PATH),
    //
    //                // create keys list
    //                newKeyNamed("firstKey").shape(SIMPLE),
    //                newKeyNamed("secondKey").shape(SIMPLE),
    //                newKeyNamed("thirdKey").shape(SIMPLE),
    //                newKeyListNamed(PAYER_KEY, List.of("firstKey", "secondKey")),
    //
    //                // perform the transaction under test
    //                cryptoCreate(PAYER)
    //                        .key(PAYER_KEY)
    //                        .balance(ONE_HUNDRED_HBARS),
    //                cryptoCreate("testAccount")
    //                        .payingWith(PAYER)
    //                        .signedBy("thirdKey")
    //                        .fee(ONE_HBAR)
    //                        .via("createAccountTxn")
    //                        .hasPrecheck(INVALID_SIGNATURE),
    //
    //                // validate charged fees
    //                assertZeroPayerFees(
    //                        CRYPTO_CREATE,
    //                        CUSTOM_SIMPLE_FEES_JSON_PATH,
    //                        INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER),
    //                restoreSimpleFees("originalSimpleFees"));
    //    }

    /** ToDo
     *  Handle time failures - still fully charged
     *  e.g. insufficient payer balance, invalid auto renew period, etc.
     */

    // ------- Helpers -------

    /**
     * Override the network simple-fees schedule with the custom JSON fees schedule
     */
    private static SpecOperation useCustomSimpleFeesFromJson(final String jsonResourcePath) {
        return withOpContext((spec, log) -> {
            final var jsonSchedule = fromClassPath(jsonResourcePath);
            FeeSchedule pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
            allRunFor(spec, overrideSimpleFees(pbjSchedule));
        });
    }

    /**
     * Assert that the charged USD for a transaction matches the expected USD amount computed with the calculator
     */
    private static SpecOperation assertFees(
            final String txnName,
            final HederaFunctionality functionality,
            final SimpleFeesParams params,
            final String jsonResourcePath,
            final SimpleFeesChargePolicy policyName) {
        return withOpContext((spec, log) -> {
            // prepare the custom fee schedule for calculations
            final var jsonSchedule = fromClassPath(jsonResourcePath);
            final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

            final Map<Extra, Long> extrasCounts = params.get();

            // calculate expected fee
            final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                    preparedSchedule, functionality, extrasCounts, policyName);

            log.info(
                    """
                    ===== Simple Fees EXPECTATION =====
                    Txn: {} ({}), Policy: {}
                    Extras: {}

                    Node Fee        : {}  ({} USD)
                    Network Fee     : {}  ({} USD)
                    Service Fee     : {}  ({} USD)

                    Node Extras     : {}  ({} USD)
                    Service Extras  : {}  ({} USD)

                    -----------------------------------
                    EXPECTED TOTAL  : {}  ({} USD)
                    EXPECTED PAYER CHARGED   : {}  ({} USD)
                    ===================================
                    """,
                    txnName,
                    functionality,
                    policyName,
                    extrasCounts,
                    expectedCharges.node(),
                    expectedCharges.nodeUsd(),
                    expectedCharges.network(),
                    expectedCharges.networkUsd(),
                    expectedCharges.service(),
                    expectedCharges.serviceUsd(),
                    expectedCharges.nodeExtras(),
                    expectedCharges.nodeExtrasUsd(),
                    expectedCharges.serviceExtras(),
                    expectedCharges.serviceExtrasUsd(),
                    expectedCharges.total(),
                    expectedCharges.totalUsd(),
                    expectedCharges.payerCharged(),
                    expectedCharges.payerChargedUsd());

            allRunFor(spec, validateChargedUsdWithin(txnName, expectedCharges.payerChargedUsd(), 0.0001));
        });
    }

    /**
     * Assert that for the given transaction the calculator returns zero charged USD
     */
    private static SpecOperation assertZeroPayerFees(
            final HederaFunctionality functionality,
            final String jsonResourcePath,
            final SimpleFeesChargePolicy policyName) {
        return withOpContext((spec, log) -> {
            // prepare the custom fee schedule for calculations
            final var jsonSchedule = fromClassPath(jsonResourcePath);
            final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

            final Map<Extra, Long> extrasCounts = SimpleFeesParams.create().get();

            // calculate expected fee
            final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                    preparedSchedule, functionality, extrasCounts, policyName);

            log.info(
                    """
                    ===== Simple Fees EXPECTATION =====
                    -----------------------------------
                    EXPECTED POLICY  : Zero-payer policy {}
                    EXPECTED PAYER CHARGED   : expected payerChargedUsd={}
                    ===================================
                    """,
                    policyName,
                    expectedCharges.payerChargedUsd());

            if (expectedCharges.payerChargedUsd() != 0L) {
                throw new AssertionError(
                        "Expected zero payer charged USD, but got " + expectedCharges.payerChargedUsd());
            }
        });
    }
}
