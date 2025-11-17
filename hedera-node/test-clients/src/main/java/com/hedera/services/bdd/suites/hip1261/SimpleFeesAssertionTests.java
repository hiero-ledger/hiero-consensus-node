// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1261;

import static com.hedera.hapi.node.base.HederaFunctionality.CONSENSUS_CREATE_TOPIC;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.validateChargedUsd;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesChargePolicy.SUCCESS_TXN_FULL_CHARGE;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesJsonLoader.fromClassPath;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.overrideSimpleFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.restoreSimpleFees;
import static com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesOps.snapshotSimpleFees;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.suites.hip1261.utils.JsonToFeeScheduleConverter;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesParams;
import com.hedera.services.bdd.suites.hip1261.utils.SimpleFeesReferenceTestCalculator;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import java.util.Map;
import java.util.stream.Stream;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;

public class SimpleFeesAssertionTests {

    private static final String PAYER = "payer";
    private static final String ADMIN_KEY = "adminKey";
    private static final String SUBMIT_KEY = "submitKey";

    @LeakyHapiTest(overrides = {"fees.simpleFeesEnabled"})
    @DisplayName("Create Topic - full charging (base only, no extras)")
    Stream<DynamicTest> createTopicWithCustomSchedule() {

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
                        .fee(ONE_HBAR)
                        .via("createTopicTxn"),

                // validate charged fees
                withOpContext((spec, log) -> {
                    // prepare the custom fee schedule for calculations
                    final var jsonSchedule = fromClassPath("/hip1261/customSimpleFees.json");
                    final var preparedSchedule = SimpleFeesReferenceTestCalculator.prepare(jsonSchedule);

                    // build the parameters: no extras
                    final Map<Extra, Long> extrasCounts =
                            SimpleFeesParams.create().get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    ExchangeRateSet exchangeRate =
                            createTopicTxn.getResponseRecord().getReceipt().getExchangeRate();

                    log.info("Exchange rate used for fee calculation: {}", exchangeRate);

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
    @DisplayName("Create Topic - full charging with extra key and signatures")
    Stream<DynamicTest> createTopicWithCustomScheduleAndExtras() {
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
                            .keys(2L) // adminKey + submitKey;
                            .signatures(2L) // payer + admin signatures, first is free
                            .bytes(0L) // blank memo
                            .get();

                    HapiGetTxnRecord createTopicTxn = getTxnRecord("createTopicTxn");

                    allRunFor(spec, createTopicTxn);

                    ExchangeRateSet exchangeRate =
                            createTopicTxn.getResponseRecord().getReceipt().getExchangeRate();

                    log.info("Exchange rate used for fee calculation: {}", exchangeRate);

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
}
