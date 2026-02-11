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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class Hip1313EnabledTest {
    private static final double CRYPTO_CREATE_BASE_FEE = 0.05;
    private static final int UTILIZATION_SCALE = 10_000;
    private static final String CRYPTO_CREATE = "CryptoCreate";

    /**
     * Base HIP-1313 curve values from the pricing spec:
     * Effective creation-rate multiplier (x) -> effective price multiplier (y).
     */
    private static final List<CurvePoint> BASE_CURVE_POINTS = List.of(
            new CurvePoint(1.0, 4),
            new CurvePoint(1.5, 8),
            new CurvePoint(2.5, 10),
            new CurvePoint(3.5, 15),
            new CurvePoint(5.0, 20),
            new CurvePoint(7.5, 30),
            new CurvePoint(10.0, 40),
            new CurvePoint(25.0, 60),
            new CurvePoint(50.0, 80),
            new CurvePoint(100.0, 100),
            new CurvePoint(250.0, 150),
            new CurvePoint(500.0, 200),
            new CurvePoint(5000.0, 200));

    /**
     * Per-functionality HIP-1313 inputs (from CSV/spec): base rate and max TPS.
     * These are converted into utilization-basis-point curves at class init.
     * * Just fo testing purpose reduced the max TPS by 100 times
     */
    private static final Map<String, TxPricingSpec> FUNCTIONALITY_PRICING_SPECS = Map.ofEntries(
            Map.entry("CryptoCreate", new TxPricingSpec(2, 10_0)),
            Map.entry("ConsensusCreateTopic", new TxPricingSpec(5, 25_000)),
            Map.entry("ScheduleCreate", new TxPricingSpec(100, 10_000)),
            Map.entry("CryptoApproveAllowance", new TxPricingSpec(10_000, 10_000)),
            Map.entry("FileCreate", new TxPricingSpec(2, 10_000)),
            Map.entry("FileAppend", new TxPricingSpec(10, 50_000)),
            Map.entry("ContractCreate", new TxPricingSpec(350, 17_500)),
            Map.entry("HookStore", new TxPricingSpec(10, 50_000)),
            Map.entry("TokenAssociateToAccount", new TxPricingSpec(100, 10_000)),
            Map.entry("TokenAirdrop", new TxPricingSpec(100, 10_000)),
            Map.entry("TokenClaimAirdrop", new TxPricingSpec(3_000, 10_500)),
            Map.entry("TokenMint", new TxPricingSpec(50, 12_500)),
            Map.entry("TokenCreate", new TxPricingSpec(100, 10_000)));

    private static final Map<String, CsvCurveMultiplierModel> CURVE_MODEL_BY_FUNCTIONALITY =
            buildFunctionalityCurveModels();

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
                    final var throttle = DeterministicThrottle.withTpsAndBurstPeriodMs(FUNCTIONALITY_PRICING_SPECS.get("CryptoCreate").maxTps(), 1000);
                    for (final var entry : entries) {
                        final var utilizationBasisPointsBefore =
                                (int) Math.round(throttle.instantaneousPercentUsed() * UTILIZATION_SCALE);
                        throttle.allow(1, entry.consensusTime());
                        final var fee = entry.txnRecord().getTransactionFee();
                        final var observedMultiplier =
                                spec.ratesProvider().toUsdWithActiveRates(fee) / CRYPTO_CREATE_BASE_FEE;
                        final var expectedMultiplier =
                                expectedMultiplierFromMap(CRYPTO_CREATE, utilizationBasisPointsBefore);
                        assertTrue(observedMultiplier >= 4, "Observed multiplier should be >= 4");
                        assertEquals(
                                expectedMultiplier,
                                observedMultiplier,
                                "Observed multiplier should match linear interpolation from provided pricing map");
                    }
                    assertEquals(200, entries.size());
                }));
    }

    private static VisibleItemsValidator feeMultiplierValidator(
            final AtomicReference<List<RecordStreamEntry>> highVolumeTxns) {
        return (spec, records) -> {
            final var items = records.get(ALL_TX_IDS);
            highVolumeTxns.set(items.entries());
        };
    }

    private static Map<String, CsvCurveMultiplierModel> buildFunctionalityCurveModels() {
        final var models = new java.util.HashMap<String, CsvCurveMultiplierModel>();
        FUNCTIONALITY_PRICING_SPECS.forEach((functionality, spec) -> {
            final var points = new ArrayList<CurvePoint>();
            for (final var basePoint : BASE_CURVE_POINTS) {
                final var effectiveRate = basePoint.rateMultiplier() * spec.baseRate();
                if (effectiveRate > spec.maxTps()) {
                    continue;
                }
                final var utilizationBasisPoints =
                        (int) Math.round((effectiveRate / spec.maxTps()) * UTILIZATION_SCALE);
                points.add(new CurvePoint(utilizationBasisPoints, basePoint.priceMultiplier()));
            }
            if (points.isEmpty()) {
                throw new IllegalStateException("No curve points generated for " + functionality);
            }
            models.put(functionality, new CsvCurveMultiplierModel(points.getLast().rateMultiplier(), points));
        });
        return models;
    }

    private static double expectedMultiplierFromMap(final String functionality, final int utilizationBasisPoints) {
        final var model = CURVE_MODEL_BY_FUNCTIONALITY.get(functionality);
        if (model == null) {
            throw new IllegalArgumentException("No pricing curve configured for " + functionality);
        }
        final var clampedUtilization = Math.max(0, Math.min(UTILIZATION_SCALE, utilizationBasisPoints));
        return interpolatePriceMultiplier(model.points(), clampedUtilization);
    }

    private static double interpolatePriceMultiplier(final List<CurvePoint> points, final double x) {
        if (points.size() == 1) {
            return points.getFirst().priceMultiplier();
        }
        if (x <= points.getFirst().rateMultiplier()) {
            return points.getFirst().priceMultiplier();
        }
        if (x >= points.getLast().rateMultiplier()) {
            return points.getLast().priceMultiplier();
        }
        for (int i = 1; i < points.size(); i++) {
            final var lower = points.get(i - 1);
            final var upper = points.get(i);
            if (x <= upper.rateMultiplier()) {
                final var xRange = upper.rateMultiplier() - lower.rateMultiplier();
                if (xRange == 0.0) {
                    return lower.priceMultiplier();
                }
                final var ratio = (x - lower.rateMultiplier()) / xRange;
                return lower.priceMultiplier() + ratio * (upper.priceMultiplier() - lower.priceMultiplier());
            }
        }
        return points.getLast().priceMultiplier();
    }

    private record CsvCurveMultiplierModel(double maxRateMultiplier, List<CurvePoint> points) {}

    private record CurvePoint(double rateMultiplier, double priceMultiplier) {}

    private record TxPricingSpec(int baseRate, int maxTps) {}
}
