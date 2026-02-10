// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.throttling.hip1313;

import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.CIVILIAN_PAYER;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECORD_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

@Tag(SIMPLE_FEES)
@HapiTestLifecycle
public class Hip1313HighLoadPricingSimulationTest {

    private static final String THROTTLES = "testSystemFiles/hip1313-pricing-sim-throttles.json";
    private static final int FEE_QUERY_RETRY_ATTEMPTS = 120;
    private static final int FEE_QUERY_RETRY_BACKOFF_MS = 25;

    @BeforeAll
    static void beforeAll(@NonNull final TestLifecycle testLifecycle) {
        testLifecycle.overrideInClass(Map.of(
                "fees.simpleFeesEnabled", "true",
                "networkAdmin.highVolumeThrottlesEnabled", "true"));
        testLifecycle.doAdhoc(cryptoCreate(CIVILIAN_PAYER).balance(ONE_MILLION_HBARS));
    }

    @LeakyHapiTest(
            requirement = {THROTTLE_OVERRIDES},
            throttles = THROTTLES)
    final Stream<DynamicTest> highLoadHighVolumePricingMatchesSimulatorTrend() {
        return hapiTest(withOpContext((spec, opLog) -> {
            allRunFor(spec, overridingThrottles(THROTTLES));

            final var baseTxn = "baseCreate";
            final var lowHvTxn = "lowHvCreate";
            allRunFor(
                    spec,
                    cryptoCreate(baseTxn)
                            .payingWith(CIVILIAN_PAYER)
                            .deferStatusResolution()
                            .via(baseTxn)
                            .hasPrecheck(OK),
                    cryptoCreate(lowHvTxn)
                            .payingWith(CIVILIAN_PAYER)
                            .withHighVolume()
                            .deferStatusResolution()
                            .via(lowHvTxn)
                            .hasPrecheck(OK));

            final long baseFee = feeFor(spec, baseTxn);
            final long lowHvFee = feeFor(spec, lowHvTxn);

            final var loadOps = new ArrayList<HapiCryptoCreate>();
            final long startNanos = System.nanoTime();
            for (int i = 0; i < 160; i++) {
                loadOps.add(cryptoCreate("hvLoad" + i)
                        .payingWith(CIVILIAN_PAYER)
                        .withHighVolume()
                        .via("hvLoad" + i)
                        .deferStatusResolution()
                        .hasPrecheckFrom(OK, BUSY));
            }
            allRunFor(spec, new ArrayList<>(loadOps));
            final double elapsedSecs = (System.nanoTime() - startNanos) / 1_000_000_000.0;
            final long accepted =
                    loadOps.stream().filter(op -> op.getActualPrecheck() == OK).count();

            final var probeTxn = "postLoadHvProbe";
            allRunFor(
                    spec,
                    cryptoCreate(probeTxn)
                            .payingWith(CIVILIAN_PAYER)
                            .withHighVolume()
                            .via(probeTxn)
                            .deferStatusResolution()
                            .hasPrecheckFrom(OK, BUSY));
            final long postLoadHvFee = feeFor(spec, probeTxn);

            final double lowRatio = (double) lowHvFee / baseFee;
            final double postRatio = (double) postLoadHvFee / baseFee;
            final double observedTps = accepted / Math.max(elapsedSecs, 0.001);
            final double expectedMultiplier = simulatedMultiplierForCryptoCreate(observedTps);

            opLog.info(
                    "HIP-1313 pricing sim: baseFee={} lowHvFee={} postLoadHvFee={} lowRatio={} postRatio={} "
                            + "accepted={} elapsedSecs={} observedTps={} expectedMultiplier={}",
                    baseFee,
                    lowHvFee,
                    postLoadHvFee,
                    lowRatio,
                    postRatio,
                    accepted,
                    elapsedSecs,
                    observedTps,
                    expectedMultiplier);

            assertTrue(lowRatio >= 3.5, "Expected low-load high-volume fee to be at least ~4x base fee");
            assertTrue(postRatio >= lowRatio, "Expected high-load high-volume fee to be >= low-load high-volume fee");
            assertTrue(
                    postRatio >= expectedMultiplier * 0.8,
                    "Expected post-load multiplier to be near simulated multiplier (within tolerance)");
        }));
    }

    private static long feeFor(final HapiSpec spec, final String txn) {
        RuntimeException lastFailure = null;
        for (int i = 0; i < FEE_QUERY_RETRY_ATTEMPTS; i++) {
            final var record = getTxnRecord(txn)
                    .assertingNothingAboutHashes()
                    .logged()
                    .hasCostAnswerPrecheckFrom(OK, BUSY, RECORD_NOT_FOUND)
                    .hasAnswerOnlyPrecheck(OK)
                    .hasRetryAnswerOnlyPrecheck(BUSY, RECORD_NOT_FOUND)
                    .setRetryLimit(200);
            try {
                allRunFor(spec, record);
                return record.getResponseRecord().getTransactionFee();
            } catch (final RuntimeException e) {
                if (!isRetriableRecordQueryFailure(e)) {
                    throw e;
                }
                lastFailure = e;
                try {
                    Thread.sleep(FEE_QUERY_RETRY_BACKOFF_MS);
                } catch (final InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while retrying fee query for " + txn, ie);
                }
            }
        }
        throw new IllegalStateException("Could not fetch txn fee for " + txn + " after retries", lastFailure);
    }

    private static boolean isRetriableRecordQueryFailure(final Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            final var message = cause.getMessage();
            if (message != null
                    && ((message.contains("costAnswerPrecheck") || message.contains("answerOnlyPrecheck"))
                            && (message.contains("BUSY") || message.contains("RECORD_NOT_FOUND")))) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Approximate simulator for HIP-1313 curve based on successful high-volume CryptoCreate TPS.
     * For CryptoCreate, baseRate=2 from expected-high-volume-pricing.json, so we estimate
     * rateMultiplierTenths ~= observedTps / baseRate.
     */
    private static double simulatedMultiplierForCryptoCreate(final double observedTps) {
        final int rateMultiplierTenths = Math.max(10, (int) Math.round(observedTps / 2.0));
        final List<int[]> curve = List.of(
                new int[] {10, 4},
                new int[] {15, 8},
                new int[] {25, 10},
                new int[] {35, 15},
                new int[] {50, 20},
                new int[] {75, 30},
                new int[] {100, 40},
                new int[] {250, 60},
                new int[] {500, 80},
                new int[] {1000, 100},
                new int[] {2500, 150},
                new int[] {5000, 200},
                new int[] {50000, 200});
        for (final var p : curve) {
            if (rateMultiplierTenths <= p[0]) {
                return p[1];
            }
        }
        return 200;
    }
}
