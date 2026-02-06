// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.resourceAsString;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.THROTTLES;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.hedera.services.bdd.junit.LeakyRepeatableHapiTest;
import com.hedera.services.bdd.junit.TargetEmbeddedMode;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.utilops.SysFileOverrideOp;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

/**
 * Integration tests for simple fees congestion pricing.
 * Verifies that the SimpleFeeCalculatorImpl correctly applies congestion multipliers
 * when network conditions warrant increased fees.
 */
@Order(-1)
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class SimpleFeesCongestionPricingTest {
    private static final Logger log = LogManager.getLogger(SimpleFeesCongestionPricingTest.class);

    private static final String CIVILIAN_ACCOUNT = "civilian";
    private static final String TEST_THROTTLES_RESOURCE = "testSystemFiles/extreme-limits.json";

    /**
     * Tests that the simple fees calculator applies congestion multipliers correctly
     * when the network is under high load. The test:
     * 1. Creates a civilian account
     * 2. Performs a transfer at normal load and captures the fee
     * 3. Configures tight throttle limits to trigger congestion
     * 4. Submits many transactions to create congestion
     * 5. Performs another transfer under congestion and verifies the fee increased
     * 6. Asserts the fee multiplier is approximately 7x as configured
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"fees.percentCongestionMultipliers", "fees.minCongestionPeriod"})
    Stream<DynamicTest> simpleFeesApplyCongestionMultiplierToTransfers() {
        AtomicLong normalPrice = new AtomicLong();
        AtomicLong congestedPrice = new AtomicLong();

        return hapiTest(
                // Setup: Create a civilian account with sufficient funds
                cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),

                // Capture normal transfer fee
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("normalTransfer"),
                getTxnRecord("normalTransfer")
                        .providingFeeTo(normalFee -> {
                            log.info("Normal transfer fee: {}", normalFee);
                            normalPrice.set(normalFee);
                        })
                        .logged(),

                // Configure congestion pricing: 7x multiplier at 1% utilization, 1 second min period
                overridingTwo("fees.percentCongestionMultipliers", "1,7x", "fees.minCongestionPeriod", "1"),

                // Apply tight throttle limits to trigger congestion
                new SysFileOverrideOp(THROTTLES, () -> resourceAsString(TEST_THROTTLES_RESOURCE)),

                // Wait for the system to recognize the new throttle config
                sleepFor(2_000),

                // Flood the network with transactions to trigger congestion
                blockingOrder(IntStream.range(0, 20)
                        .mapToObj(i -> new HapiSpecOperation[] {
                            usableTxnIdNamed("uncheckedTxn" + i).payerId(CIVILIAN_ACCOUNT),
                            uncheckedSubmit(cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                                            .payingWith(CIVILIAN_ACCOUNT))
                                    .payingWith(GENESIS)
                                    .noLogging()
                        })
                        .flatMap(Arrays::stream)
                        .toArray(HapiSpecOperation[]::new)),

                // Capture congested transfer fee
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("congestedTransfer"),
                getTxnRecord("congestedTransfer").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                    log.info("Congested transfer fee: {}", congestionFee);
                    congestedPrice.set(congestionFee);
                }),

                // Verify the congestion multiplier was applied (~7x)
                withOpContext((spec, opLog) -> {
                    double ratio = (1.0 * congestedPrice.get()) / normalPrice.get();
                    log.info("Fee ratio (congested/normal): {}", ratio);
                    Assertions.assertEquals(7.0, ratio, 0.5, "~7x congestion multiplier should be in effect");
                }));
    }
}
