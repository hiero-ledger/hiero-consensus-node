// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling.hip1313;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.allVisibleItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.streamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.updateLargeFile;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.SIMPLE_FEE_SCHEDULE;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.CRYPTO_CREATE_BASE_FEE;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.CRYPTO_CREATE_HV_TPS;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.LINEAR_CRYPTO_CREATE_MAX_MULTIPLIER;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.ONE_X_MULTIPLIER;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.SCHEDULE_CREATE_HV_TPS;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.SCHEDULE_CREATE_MULTIPLIER_MAP;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.TOPIC_CREATE_BASE_FEE;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.TOPIC_CREATE_HV_TPS;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.assertAnyRecordMatches;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.assertHighVolumeMultiplierSet;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.assertMultiplierAtLeast;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.assertMultiplierMatchesExpectation;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.assertNoRecordMatches;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.feeMultiplierValidator;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.filteredHighVolumeEntries;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.observedMultiplier;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.simpleFeesWithOneXCryptoCreateHighVolumeRates;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.simpleFeesWithoutCryptoCreatePricingCurve;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.submitHighVolumeCryptoCreates;
import static com.hedera.services.bdd.suites.throttling.hip1313.Hip1313EnabledTest.submitMixedHighVolumeTopicAndScheduleCreates;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.linearInterpolate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

// Genesis tests build their own network, so they must run before any shared network exists
@Order(Integer.MIN_VALUE)
@Tag(SIMPLE_FEES)
public class Hip1313GenesisTest {

    @GenesisHapiTest
    final Stream<DynamicTest> highVolumeTxnsWorkAsExpectedForCryptoCreate() {
        final AtomicReference<List<RecordStreamEntry>> highVolumeTxns = new AtomicReference<>();
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-pricing-sim-throttles.json"),
                streamMustIncludeNoFailuresFrom(allVisibleItems(feeMultiplierValidator(highVolumeTxns))),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overriding("networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> submitHighVolumeCryptoCreates(spec, 200)),
                // ensure one record is closed
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                withOpContext((spec, opLog) -> {
                    final var entries = filteredHighVolumeEntries(highVolumeTxns, e -> true);
                    final var throttle = DeterministicThrottle.withTpsAndBurstPeriodMs(CRYPTO_CREATE_HV_TPS, 1000);
                    var numCreateTxnsAllowed = 0;
                    for (final var entry : entries) {
                        throttle.leakUntil(entry.consensusTime());
                        final var utilizationBasisPointsBefore = throttle.instantaneousBps();
                        throttle.allow(1, entry.consensusTime());
                        numCreateTxnsAllowed++;
                        final var utilizationBasisPointsAfter = throttle.instantaneousBps();
                        assertHighVolumeMultiplierSet(entry, "crypto create");
                        final var fee = entry.txnRecord().getTransactionFee();
                        final var observedMultiplier = observedMultiplier(spec, fee, CRYPTO_CREATE_BASE_FEE);
                        final var observedRawMultiplier = entry.txnRecord().getHighVolumePricingMultiplier() / 1000.0;
                        assertMultiplierAtLeast(observedMultiplier, "crypto create");
                        assertMultiplierMatchesExpectation(
                                CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP,
                                observedRawMultiplier,
                                utilizationBasisPointsBefore,
                                utilizationBasisPointsAfter,
                                "crypto create",
                                numCreateTxnsAllowed);
                    }
                    assertEquals(200, entries.size());
                }));
    }

    @GenesisHapiTest
    @Disabled
    final Stream<DynamicTest> mixedHighVolumeTxnsWorkAsExpectedForTopicCreateAndScheduleCreate() {
        final AtomicReference<List<RecordStreamEntry>> highVolumeTxns = new AtomicReference<>();
        final int numBursts = 200;
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-multi-op-pricing-throttles.json"),
                streamMustIncludeNoFailuresFrom(allVisibleItems(feeMultiplierValidator(highVolumeTxns))),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overriding("networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> submitMixedHighVolumeTopicAndScheduleCreates(spec, numBursts)),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                withOpContext((spec, opLog) -> {
                    final var entries = filteredHighVolumeEntries(
                            highVolumeTxns,
                            e -> e.body().hasConsensusCreateTopic() || e.body().hasScheduleCreate());
                    final var topicThrottle = DeterministicThrottle.withTpsAndBurstPeriodMs(TOPIC_CREATE_HV_TPS, 1000);
                    final var scheduleThrottle =
                            DeterministicThrottle.withTpsAndBurstPeriodMs(SCHEDULE_CREATE_HV_TPS, 1000);
                    int topicCreates = 0;
                    int scheduleCreates = 0;
                    for (final var entry : entries) {
                        final var fee = entry.txnRecord().getTransactionFee();
                        if (entry.body().hasConsensusCreateTopic()) {
                            topicThrottle.leakUntil(entry.consensusTime());
                            final var utilizationBasisPointsBefore = topicThrottle.instantaneousBps();
                            topicThrottle.allow(1, entry.consensusTime());
                            topicCreates++;
                            final var utilizationBasisPointsAfter = topicThrottle.instantaneousBps();
                            assertHighVolumeMultiplierSet(entry, "topic create");
                            final var observedMultiplier = observedMultiplier(spec, fee, TOPIC_CREATE_BASE_FEE);
                            final var observedRawMultiplier =
                                    entry.txnRecord().getHighVolumePricingMultiplier() / 1000.0;
                            assertMultiplierAtLeast(observedMultiplier, "topic create");
                            assertMultiplierMatchesExpectation(
                                    CRYPTO_TOPIC_CREATE_MULTIPLIER_MAP,
                                    observedRawMultiplier,
                                    utilizationBasisPointsBefore,
                                    utilizationBasisPointsAfter,
                                    "topic create",
                                    topicCreates);
                        } else if (entry.body().hasScheduleCreate()) {
                            scheduleThrottle.leakUntil(entry.consensusTime());
                            final var utilizationBasisPointsBefore = scheduleThrottle.instantaneousBps();
                            scheduleThrottle.allow(1, entry.consensusTime());
                            scheduleCreates++;
                            final var utilizationBasisPointsAfter = scheduleThrottle.instantaneousBps();
                            assertHighVolumeMultiplierSet(entry, "schedule create");
                            final var observedRawMultiplier =
                                    entry.txnRecord().getHighVolumePricingMultiplier() / 1000.0;
                            assertMultiplierMatchesExpectation(
                                    SCHEDULE_CREATE_MULTIPLIER_MAP,
                                    observedRawMultiplier,
                                    utilizationBasisPointsBefore,
                                    utilizationBasisPointsAfter,
                                    "schedule create",
                                    scheduleCreates);
                        }
                    }
                    assertEquals(numBursts * 2, entries.size());
                    assertEquals(numBursts, topicCreates);
                    assertEquals(numBursts, scheduleCreates);
                }));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> cryptoCreateUsesLinearInterpolationWhenPricingCurveMissing() {
        final AtomicReference<List<RecordStreamEntry>> highVolumeTxns = new AtomicReference<>();
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-pricing-sim-throttles.json"),
                streamMustIncludeNoFailuresFrom(allVisibleItems(feeMultiplierValidator(highVolumeTxns))),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overriding("networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            getFileContents(SIMPLE_FEE_SCHEDULE)
                                    .consumedBy(bytes -> originalSimpleFeeSchedule.set(ByteString.copyFrom(bytes))));
                    allRunFor(
                            spec,
                            updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, simpleFeesWithoutCryptoCreatePricingCurve()));
                    assertTrue(
                            spec.tryReinitializingFees(),
                            "Failed to reinitialize fees after overriding simple fee schedule");
                }),
                withOpContext((spec, opLog) -> submitHighVolumeCryptoCreates(spec, 200)),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                withOpContext((spec, opLog) -> {
                    try {
                        final var entries = filteredHighVolumeEntries(
                                highVolumeTxns, e -> e.body().hasCryptoCreateAccount());
                        final var throttle = DeterministicThrottle.withTpsAndBurstPeriodMs(CRYPTO_CREATE_HV_TPS, 1000);
                        for (final var entry : entries) {
                            throttle.leakUntil(entry.consensusTime());
                            final var utilizationBasisPointsBefore = throttle.instantaneousBps();
                            throttle.allow(1, entry.consensusTime());
                            final long expectedRawMultiplier = linearInterpolate(
                                    0,
                                    1000L,
                                    10_000,
                                    LINEAR_CRYPTO_CREATE_MAX_MULTIPLIER,
                                    utilizationBasisPointsBefore);
                            final long expectedMultiplier = Math.max(1000L, expectedRawMultiplier);
                            // Proto default is 0 when field is not present; treat this as the default multiplier 1x.
                            final var actualMultiplier =
                                    Math.max(1000L, entry.txnRecord().getHighVolumePricingMultiplier());
                            assertEquals(
                                    expectedMultiplier,
                                    actualMultiplier,
                                    "Given BPS of " + utilizationBasisPointsBefore
                                            + ", expected linear interpolated multiplier " + expectedMultiplier
                                            + " but found " + actualMultiplier);
                        }
                        assertEquals(200, entries.size());
                    } finally {
                        final var snapshot = originalSimpleFeeSchedule.get();
                        if (snapshot != null) {
                            allRunFor(spec, updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, snapshot));
                            assertTrue(
                                    spec.tryReinitializingFees(),
                                    "Failed to reinitialize fees after restoring simple fee schedule");
                        }
                    }
                }));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> cryptoCreateWithHighVolumeUsesDefaultMultiplierWhenMaxIsOneX() {
        final AtomicReference<ByteString> originalSimpleFeeSchedule = new AtomicReference<>();
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-pricing-sim-throttles.json"),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overriding("networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            getFileContents(SIMPLE_FEE_SCHEDULE)
                                    .consumedBy(bytes -> originalSimpleFeeSchedule.set(ByteString.copyFrom(bytes))));
                    allRunFor(
                            spec,
                            updateLargeFile(
                                    GENESIS, SIMPLE_FEE_SCHEDULE, simpleFeesWithOneXCryptoCreateHighVolumeRates()));
                    assertTrue(
                            spec.tryReinitializingFees(),
                            "Failed to reinitialize fees after overriding simple fee schedule");
                }),
                cryptoCreate("defaultMultiplierCreate")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .via("defaultMultiplierCreateTxn"),
                getTxnRecord("defaultMultiplierCreateTxn")
                        .andAllChildRecords()
                        .exposingAllTo(records -> {
                            assertAnyRecordMatches(
                                    records, record -> record.getHighVolumePricingMultiplier() == ONE_X_MULTIPLIER);
                            assertNoRecordMatches(
                                    records, record -> record.getHighVolumePricingMultiplier() > ONE_X_MULTIPLIER);
                        })
                        .logged(),
                withOpContext((spec, opLog) -> {
                    final var snapshot = originalSimpleFeeSchedule.get();
                    if (snapshot != null) {
                        allRunFor(spec, updateLargeFile(GENESIS, SIMPLE_FEE_SCHEDULE, snapshot));
                        assertTrue(
                                spec.tryReinitializingFees(),
                                "Failed to reinitialize fees after restoring simple fee schedule");
                    }
                }));
    }
}
