// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.TestTags.SIMPLE_FEES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.SysFileOverrideOp.Target.THROTTLES;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;

import com.hedera.services.bdd.junit.LeakyHapiTest;
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
import org.junit.jupiter.api.Tag;

/**
 * Integration tests for simple fees congestion pricing.
 */
@Tag(SIMPLE_FEES)
public class SimpleFeesCongestionPricingTest {
    private static final Logger log = LogManager.getLogger(SimpleFeesCongestionPricingTest.class);

    private static final String CIVILIAN_ACCOUNT = "civilian";

    /**
     * Throttle config with low throughput limits (500 milliOps/sec, 60s burst) to
     * trigger congestion pricing in virtual time mode.
     */
    private static final String CONGESTION_THROTTLES = """
            {
              "buckets": [
                {
                  "name": "ThroughputLimits",
                  "burstPeriod": 60,
                  "throttleGroups": [
                    {
                      "milliOpsPerSec": 500,
                      "operations": ["CryptoTransfer"]
                    }
                  ]
                },
                {
                  "name": "QueryLimits",
                  "burstPeriod": 60,
                  "throttleGroups": [
                    {
                      "opsPerSec": 100,
                      "operations": [
                        "CryptoGetAccountBalance", "FileGetContents",
                        "FileGetInfo", "TransactionGetRecord",
                        "TransactionGetReceipt"
                      ]
                    }
                  ]
                }
              ]
            }""";

    @LeakyHapiTest(
            overrides = {"fees.percentCongestionMultipliers", "fees.minCongestionPeriod", "fees.simpleFeesEnabled"})
    Stream<DynamicTest> simpleFeesApplyCongestionMultiplierToTransfers() {
        AtomicLong normalPrice = new AtomicLong();
        AtomicLong congestedPrice = new AtomicLong();
        final int burstTxns = 24;

        return hapiTest(
                overriding("fees.simpleFeesEnabled", "true"),
                cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("normalTransfer"),
                getTxnRecord("normalTransfer")
                        .providingFeeTo(normalFee -> {
                            log.info("Normal transfer fee: {}", normalFee);
                            normalPrice.set(normalFee);
                        })
                        .logged(),
                overridingTwo("fees.percentCongestionMultipliers", "1,7x", "fees.minCongestionPeriod", "1"),
                new SysFileOverrideOp(THROTTLES, () -> CONGESTION_THROTTLES),
                sleepFor(2_000),
                blockingOrder(IntStream.range(0, burstTxns)
                        .mapToObj(i -> new HapiSpecOperation[] {
                            usableTxnIdNamed("uncheckedTxn" + i).payerId(CIVILIAN_ACCOUNT),
                            uncheckedSubmit(cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                                            .payingWith(CIVILIAN_ACCOUNT)
                                            .txnId("uncheckedTxn" + i))
                                    .payingWith(GENESIS)
                                    .noLogging(),
                            sleepFor(125)
                        })
                        .flatMap(Arrays::stream)
                        .toArray(HapiSpecOperation[]::new)),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("congestedTransfer")
                        .hasRetryPrecheckFrom(BUSY)
                        .setRetryLimit(20),
                sleepFor(2_000),
                getTxnRecord("congestedTransfer").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                    log.info("Congested transfer fee: {}", congestionFee);
                    congestedPrice.set(congestionFee);
                }),
                withOpContext((spec, opLog) -> Assertions.assertEquals(
                        7.0,
                        (1.0 * congestedPrice.get()) / normalPrice.get(),
                        0.1,
                        "~7x congestion multiplier should be in effect")));
    }
}
