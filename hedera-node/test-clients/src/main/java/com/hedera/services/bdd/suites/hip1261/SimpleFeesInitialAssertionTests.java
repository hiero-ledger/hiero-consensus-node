// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_UPDATE_TOPIC;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.updateTopic;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.SUCCESS_TXN_FULL_CHARGE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesJsonLoader.fromClassPath;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.overrideSimpleFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.restoreSimpleFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.snapshotSimpleFees;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SIGNATURE;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.suites.hip1261.utils.JsonToFeeScheduleConverter;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesParams;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesReferenceTestCalculator;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
public class SimpleFeesInitialAssertionTests {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";
    private static final String NEW_ADMIN_KEY = "newAdminKey";
    private static final String NEW_SUBMIT_KEY = "newSubmitKey";

    @HapiTest
    //    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - full charging with included signatures")
    Stream<DynamicTest> createTopicWithCustomScheduleWithoutExtras() {

        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    FeeSchedule pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                createTopic("testTopic")
                        .blankMemo()
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .signatures(1L) // payer signature is included
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule, CONSENSUS_CREATE_TOPIC, extrasCounts, SUCCESS_TXN_FULL_CHARGE);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("createTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - full charging with extra key")
    Stream<DynamicTest> createTopicWithCustomScheduleWithExtraKey() {

        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    FeeSchedule pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                createTopic("testTopic")
                        .blankMemo()
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .signatures(1L) // payer signature is included
                            .keys(1L) // adminKey not included
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule, CONSENSUS_CREATE_TOPIC, extrasCounts, SUCCESS_TXN_FULL_CHARGE);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("createTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - full charging with extra key and signature")
    Stream<DynamicTest> createTopicWithCustomScheduleWithExtraSignature() {

        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    FeeSchedule pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                createTopic("testTopic")
                        .blankMemo()
                        .adminKeyName(ADMIN_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .signatures(2L) // payer signature is included
                            .keys(1L) // adminKey not included
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule, CONSENSUS_CREATE_TOPIC, extrasCounts, SUCCESS_TXN_FULL_CHARGE);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("createTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - full charging with extra keys and extra signatures")
    Stream<DynamicTest> createTopicWithCustomScheduleAndExtraKeysAndSignatures() {
        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUBMIT_KEY),
                createTopic("testTopic")
                        .blankMemo()
                        .adminKeyName(ADMIN_KEY)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY, SUBMIT_KEY)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .keys(2L) // adminKey + submitKey;
                            .signatures(2L) // payer + admin signatures, first is free
                            .bytes(0L) // blank memo
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule, CONSENSUS_CREATE_TOPIC, extrasCounts, SUCCESS_TXN_FULL_CHARGE);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("createTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - full charging with extra memo bytes")
    Stream<DynamicTest> createTopicWithCustomScheduleAndMemoBytes() {
        final String memoText = "This is a test topic memo";
        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(SUBMIT_KEY),
                createTopic("testTopic")
                        .memo(memoText)
                        .payingWith(PAYER)
                        .signedBy(PAYER)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    final long memoBytes = memoText.getBytes().length;

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .signatures(1L)
                            .bytes(memoBytes)
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule, CONSENSUS_CREATE_TOPIC, extrasCounts, SUCCESS_TXN_FULL_CHARGE);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("createTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - full charging with extra keys, signatures and bytes")
    Stream<DynamicTest> createTopicWithCustomScheduleAndExtraKeysSignaturesAndBytes() {
        final String memoText = "This is a test topic memo";
        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUBMIT_KEY),
                createTopic("testTopic")
                        .memo(memoText)
                        .adminKeyName(ADMIN_KEY)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY, SUBMIT_KEY)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    final long memoBytes = memoText.getBytes().length;

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .keys(2L) // adminKey + submitKey;
                            .signatures(2L) // payer + admin signatures, first is free
                            .bytes(memoBytes)
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule, CONSENSUS_CREATE_TOPIC, extrasCounts, SUCCESS_TXN_FULL_CHARGE);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("createTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Update Topic - full charging with extra keys, signatures and bytes")
    Stream<DynamicTest> updateTopicWithCustomScheduleAndExtraKeysSignaturesAndBytes() {
        final String memoText = "This is a test topic memo";
        final String newMemoText = "This is an updated test topic memo";
        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // create the topic
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                newKeyNamed(SUBMIT_KEY),
                newKeyNamed(NEW_ADMIN_KEY),
                newKeyNamed(NEW_SUBMIT_KEY),
                createTopic("testTopic")
                        .memo(memoText)
                        .adminKeyName(ADMIN_KEY)
                        .submitKeyName(SUBMIT_KEY)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(NEW_ADMIN_KEY),
                newKeyNamed(NEW_SUBMIT_KEY),
                updateTopic("testTopic")
                        .adminKey(NEW_ADMIN_KEY)
                        .submitKey(NEW_SUBMIT_KEY)
                        .memo(newMemoText)
                        .payingWith(PAYER)
                        .signedBy(PAYER, ADMIN_KEY, NEW_ADMIN_KEY)
                        .fee(ONE_HBAR)
                        .via("updateTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    final long newMemoBytes = newMemoText.getBytes().length;

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .keys(1L) // newAdminKey + newSubmitKey;
                            .signatures(2L) // payer + admin signatures
                            .bytes(newMemoBytes)
                            .get();

                    HapiGetTxnRecord updateTopicTxn = getTxnRecord("updateTopicTxn");

                    allRunFor(spec, updateTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule, CONSENSUS_UPDATE_TOPIC, extrasCounts, SUCCESS_TXN_FULL_CHARGE);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("updateTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - confirm zero payer policy")
    Stream<DynamicTest> createTopicConfirmZeroPayerPolicy() {

        return hapiTest(
                // save the current active schedule to registry
                snapshotSimpleFees("originalSimpleFees"),

                // override the active schedule with custom schedule for this test
                withOpContext((spec, log) -> {
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    FeeSchedule pbjSchedule = JsonToFeeScheduleConverter.toFeeSchedule(jsonSchedule);
                    allRunFor(spec, overrideSimpleFees(pbjSchedule));
                }),

                // perform the transaction under test
                cryptoCreate(PAYER).balance(ONE_HUNDRED_HBARS),
                newKeyNamed(ADMIN_KEY),
                createTopic("testTopic")
                        .blankMemo()
                        .payingWith(PAYER)
                        .signedBy(ADMIN_KEY)
                        .fee(ONE_HBAR)
                        .via("createTopicTxn")
                        .hasKnownStatus(INVALID_SIGNATURE),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    // build the parameters
                    final Map<Extra, Long> extrasCounts = SimpleFeesParams.create()
                            .signatures(1L) // payer signature is included
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    // calculate expected fee
                    final var expectedCharges = SimpleFeesReferenceTestCalculator.computeWithPolicy(
                            preparedSchedule,
                            CONSENSUS_CREATE_TOPIC,
                            extrasCounts,
                            INVALID_TXN_AT_PRE_HANDLE_ZERO_PAYER);

                    log.info(
                            "expected node = {}, network = {}, service = {}, total = {}",
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.node()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.network()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.service()),
                            SimpleFeesReferenceTestCalculator.toUsd(expectedCharges.payerCharged()));

                    allRunFor(spec, validateChargedUsd("createTopicTxn", expectedCharges.payerUsd()));
                }),
                restoreSimpleFees("originalSimpleFees"));
    }
}
