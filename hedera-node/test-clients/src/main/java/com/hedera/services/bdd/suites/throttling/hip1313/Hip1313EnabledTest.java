// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling.hip1313;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.allVisibleItems;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doingContextual;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.recordStreamMustIncludeNoFailuresFrom;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsAssertion.ALL_TX_IDS;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.MULTIPLIER_SCALE;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.UTILIZATION_SCALE;
import static org.hiero.hapi.fees.HighVolumePricingCalculator.linearInterpolate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.node.app.hapi.utils.forensics.RecordStreamEntry;
import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.services.bdd.junit.GenesisHapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.streams.assertions.VisibleItemsValidator;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class Hip1313EnabledTest {
    private static final double CRYPTO_CREATE_BASE_FEE = 0.05;
    private static final int CRYPTO_CREATE_HV_TPS = 800;
    public static final NavigableMap<Integer, Long> UTILIZATION_MULTIPLIER_MAP = new TreeMap<>(Map.ofEntries(
            Map.entry(2, 4000L),
            Map.entry(3, 8000L),
            Map.entry(5, 10000L),
            Map.entry(7, 15000L),
            Map.entry(10, 20000L),
            Map.entry(15, 30000L),
            Map.entry(20, 40000L),
            Map.entry(50, 60000L),
            Map.entry(100, 80000L),
            Map.entry(200, 100000L),
            Map.entry(500, 150000L),
            Map.entry(1000, 200000L),
            Map.entry(10000, 200000L)));

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "networkAdmin.highVolumeThrottlesEnabled", "true"));
        testLifecycle.doAdhoc(cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS));
    }

    @LeakyHapiTest(
            requirement = {THROTTLE_OVERRIDES},
            throttles = "testSystemFiles/hip1313-high-volume-total-throttle.json")
    final Stream<DynamicTest> totalHighVolumeThrottleAppliesAcrossDifferentFunctionalities() {
        return hapiTest(
                cryptoCreate("hvTotalCreate")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .hasPrecheck(OK)
                        .via("createAccount"),
                getTxnRecord("createAccount").logged(),
                createTopic("createAccount")
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .hasPrecheck(BUSY));
    }

    @GenesisHapiTest
    final Stream<DynamicTest> highVolumeTxnsWorkAsExpectedForCryptoCreate() {
        final AtomicReference<List<RecordStreamEntry>> highVolumeTxns = new AtomicReference<>();
        return hapiTest(
                overridingThrottles("testSystemFiles/hip1313-pricing-sim-throttles.json"),
                recordStreamMustIncludeNoFailuresFrom(allVisibleItems(feeMultiplierValidator(highVolumeTxns))),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS),
                overridingTwo("fees.simpleFeesEnabled", "true", "networkAdmin.highVolumeThrottlesEnabled", "true"),
                withOpContext((spec, opLog) -> {
                    for (int i = 0; i < 200; i++) {
                        allRunFor(
                                spec,
                                cryptoCreate("hvTotalCreate" + i)
                                        .payingWith(CIVILIAN_PAYER)
                                        .deferStatusResolution()
                                        .withHighVolume());
                    }
                }),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                doingContextual(TxnUtils::triggerAndCloseAtLeastOneFileIfNotInterrupted),
                withOpContext((spec, opLog) -> {
                    final var entries = highVolumeTxns.get().stream()
                            .filter(e -> e.body().getHighVolume())
                            .toList();
                    final var throttle = DeterministicThrottle.withTpsAndBurstPeriodMs(CRYPTO_CREATE_HV_TPS, 1000);
                    for (final var entry : entries) {
                        final var utilizationBasisPointsBefore =
                                (int) Math.round(throttle.instantaneousPercentUsed() * 100);
                        throttle.allow(1, entry.consensusTime());
                        final var fee = entry.txnRecord().getTransactionFee();
                        final var observedMultiplier =
                                spec.ratesProvider().toUsdWithActiveRates(fee) / CRYPTO_CREATE_BASE_FEE;
                        final var expectedMultiplier = getInterpolatedMultiplier(utilizationBasisPointsBefore) / 1000.0;
                        assertTrue(observedMultiplier >= 4, "Observed multiplier should be >= 4");
                        assertEquals(
                                expectedMultiplier,
                                observedMultiplier,
                                0.001,
                                "Given BPS of " + utilizationBasisPointsBefore
                                        + " Observed multiplier " + observedMultiplier
                                        + " does not match expected multiplier " + expectedMultiplier);
                    }
                    assertEquals(200, entries.size());
                }));
    }

    public static long getInterpolatedMultiplier(final int utilizationBasisPoints) {
        final NavigableMap<Integer, Long> map = UTILIZATION_MULTIPLIER_MAP;
        final int clampedUtilization = Math.max(0, Math.min(utilizationBasisPoints, UTILIZATION_SCALE));
        final long maxMultiplier = Math.max(map.lastEntry().getValue(), MULTIPLIER_SCALE);

        long rawMultiplier;
        if (map.size() == 1) {
            rawMultiplier = normalizeMultiplier(map.firstEntry().getValue());
        } else {
            Map.Entry<Integer, Long> lower = null;
            Map.Entry<Integer, Long> upper = null;

            for (final var entry : map.entrySet()) {
                if (entry.getKey() <= clampedUtilization) {
                    lower = entry;
                }
                if (entry.getKey() >= clampedUtilization && upper == null) {
                    upper = entry;
                }
            }

            if (lower == null) {
                rawMultiplier = normalizeMultiplier(map.firstEntry().getValue());
            } else if (upper == null) {
                rawMultiplier = normalizeMultiplier(map.lastEntry().getValue());
            } else if (lower.getKey().equals(upper.getKey())) {
                rawMultiplier = normalizeMultiplier(upper.getValue());
            } else {
                rawMultiplier = linearInterpolate(
                        lower.getKey(),
                        normalizeMultiplier(lower.getValue()),
                        upper.getKey(),
                        normalizeMultiplier(upper.getValue()),
                        clampedUtilization);
            }
        }

        return Math.max(MULTIPLIER_SCALE, Math.min(rawMultiplier, maxMultiplier));
    }

    private static long normalizeMultiplier(final long multiplier) {
        return Math.max(multiplier, MULTIPLIER_SCALE);
    }

    private static VisibleItemsValidator feeMultiplierValidator(
            final AtomicReference<List<RecordStreamEntry>> highVolumeTxns) {
        return (spec, records) -> {
            final var items = records.get(ALL_TX_IDS);
            highVolumeTxns.set(items.entries());
        };
    }
}
