// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
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
 * Tests that congestion multiplier is correctly applied to simple fees.
 * This ensures that when simple fees are enabled, the congestion pricing
 * mechanism works identically to legacy fees.
 */
@Order(-1)
@Tag(INTEGRATION)
@Tag(SIMPLE_FEES)
@TargetEmbeddedMode(REPEATABLE)
public class CongestionSimpleFeesSuite {
    private static final Logger log = LogManager.getLogger(CongestionSimpleFeesSuite.class);

    private static final String CIVILIAN_ACCOUNT = "civilian";

    /**
     * Tests that congestion multiplier is applied to simple fees for crypto transfers.
     * This test:
     * 1. Creates a civilian account with sufficient balance
     * 2. Captures the normal fee for a crypto transfer (with multiplier = 1)
     * 3. Configures congestion pricing with a 7x multiplier
     * 4. Overrides throttles to create artificial congestion
     * 5. Submits many transactions to trigger congestion
     * 6. Captures the congested fee
     * 7. Verifies that the congested fee is approximately 7x the normal fee
     */
    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"fees.percentCongestionMultipliers", "fees.minCongestionPeriod"})
    Stream<DynamicTest> simpleFeesCongestionMultiplierApplied() {
        AtomicLong normalPrice = new AtomicLong();
        AtomicLong congestedPrice = new AtomicLong();

        return hapiTest(
                // Create civilian account with enough balance
                cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),

                // Capture normal fee (no congestion)
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("normalTransfer"),
                getTxnRecord("normalTransfer")
                        .providingFeeTo(normalFee -> {
                            log.info("Normal simple fee for transfer is {}", normalFee);
                            normalPrice.set(normalFee);
                        })
                        .logged(),

                // Configure congestion pricing: 7x multiplier after 1% congestion
                overridingTwo("fees.percentCongestionMultipliers", "1,7x", "fees.minCongestionPeriod", "1"),

                // Override throttles to create artificial congestion (very low limits)
                new SysFileOverrideOp(THROTTLES, () -> resourceAsString("testSystemFiles/extreme-limits.json")),

                // Sleep to let throttle config take effect
                sleepFor(2_000),

                // Create congestion by submitting many transactions quickly
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

                // Now submit a transaction during congestion and capture the fee
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("congestedTransfer"),
                getTxnRecord("congestedTransfer").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                    log.info("Congestion simple fee for transfer is {}", congestionFee);
                    congestedPrice.set(congestionFee);
                }),

                // Verify approximately 7x multiplier is applied
                withOpContext((spec, opLog) -> Assertions.assertEquals(
                        7.0,
                        (1.0 * congestedPrice.get()) / normalPrice.get(),
                        0.1,
                        "~7x multiplier should be in effect for simple fees")));
    }
}
