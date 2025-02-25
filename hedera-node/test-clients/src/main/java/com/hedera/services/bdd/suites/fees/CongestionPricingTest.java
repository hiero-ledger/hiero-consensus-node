// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.fees;

import static com.hedera.services.bdd.junit.ContextRequirement.PROPERTY_OVERRIDES;
import static com.hedera.services.bdd.junit.ContextRequirement.THROTTLE_OVERRIDES;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DynamicTest;

public class CongestionPricingTest {
    private static final Logger log = LogManager.getLogger(CongestionPricingTest.class);

    private static final String CIVILIAN_ACCOUNT = "civilian";
    private static final String SECOND_ACCOUNT = "second";
    private static final String FEE_MONITOR_ACCOUNT = "feeMonitor";

    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"contracts.maxGasPerSec", "fees.percentCongestionMultipliers", "fees.minCongestionPeriod"})
    final Stream<DynamicTest> canUpdateMultipliersDynamically() {
        final var contract = "Multipurpose";
        final var tmpMinCongestionPeriod = "1";

        AtomicLong normalPrice = new AtomicLong();
        AtomicLong sevenXPrice = new AtomicLong();
        // Send enough gas with each transaction to keep the throttle over the
        // 1% of 15M = 150_000 congestion limit
        final var gasToOffer = 200_000L;

        return hapiTest(
                overriding("contracts.maxGasPerSec", "15_000_000"),
                cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                uploadInitCode(contract),
                contractCreate(contract),
                contractCall(contract)
                        .payingWith(CIVILIAN_ACCOUNT)
                        .fee(ONE_HUNDRED_HBARS)
                        .gas(gasToOffer)
                        .sending(ONE_HBAR)
                        .via("cheapCall"),
                getTxnRecord("cheapCall")
                        .providingFeeTo(normalFee -> {
                            log.info("Normal fee is {}", normalFee);
                            normalPrice.set(normalFee);
                        })
                        .logged(),
                overridingTwo(
                        "fees.percentCongestionMultipliers",
                        "1,7x",
                        "fees.minCongestionPeriod",
                        tmpMinCongestionPeriod),
                overridingThrottles("testSystemFiles/artificial-limits-congestion.json"),
                sleepFor(2_000),
                blockingOrder(IntStream.range(0, 10)
                        .mapToObj(i -> new HapiSpecOperation[] {
                            usableTxnIdNamed("uncheckedTxn" + i).payerId(CIVILIAN_ACCOUNT),
                            uncheckedSubmit(contractCall(contract)
                                            .signedBy(CIVILIAN_ACCOUNT)
                                            .fee(ONE_HUNDRED_HBARS)
                                            .gas(gasToOffer)
                                            .sending(ONE_HBAR)
                                            .txnId("uncheckedTxn" + i))
                                    .payingWith(GENESIS),
                            sleepFor(125)
                        })
                        .flatMap(Arrays::stream)
                        .toArray(HapiSpecOperation[]::new)),
                contractCall(contract)
                        .payingWith(CIVILIAN_ACCOUNT)
                        .fee(ONE_HUNDRED_HBARS)
                        .gas(gasToOffer)
                        .sending(ONE_HBAR)
                        .via("pricyCall"),
                getReceipt("pricyCall").logged(),
                getTxnRecord("pricyCall").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                    log.info("Congestion fee is {}", congestionFee);
                    sevenXPrice.set(congestionFee);
                }),
                withOpContext((spec, opLog) -> Assertions.assertEquals(
                        7.0,
                        (1.0 * sevenXPrice.get()) / normalPrice.get(),
                        0.1,
                        "~7x multiplier should be in affect!")));
    }

    @LeakyHapiTest(
            requirement = {PROPERTY_OVERRIDES, THROTTLE_OVERRIDES},
            overrides = {"fees.percentCongestionMultipliers", "fees.minCongestionPeriod"})
    final Stream<DynamicTest> canUpdateMultipliersDynamically2() {
        String tmpMinCongestionPeriod = "1";

        AtomicLong normalPrice = new AtomicLong();
        AtomicLong sevenXPrice = new AtomicLong();

        return hapiTest(
                cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                cryptoCreate(SECOND_ACCOUNT).payingWith(GENESIS).balance(ONE_HBAR),
                cryptoCreate(FEE_MONITOR_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(CIVILIAN_ACCOUNT, SECOND_ACCOUNT, 5L))
                        .payingWith(FEE_MONITOR_ACCOUNT)
                        .via("cheapCall"),
                getTxnRecord("cheapCall")
                        .providingFeeTo(normalFee -> {
                            log.info("Normal fee is {}", normalFee);
                            normalPrice.set(normalFee);
                        })
                        .logged(),
                overridingTwo(
                        "fees.percentCongestionMultipliers",
                        "1,7x",
                        "fees.minCongestionPeriod",
                        tmpMinCongestionPeriod),
                overridingThrottles("testSystemFiles/artificial-limits-congestion.json"),
                sleepFor(2_000),
                blockingOrder(IntStream.range(0, 20)
                        .mapToObj(i -> new HapiSpecOperation[] {
                            usableTxnIdNamed("uncheckedTxn" + i).payerId(CIVILIAN_ACCOUNT),
                            uncheckedSubmit(cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(
                                                    CIVILIAN_ACCOUNT, SECOND_ACCOUNT, 5L))
                                            .txnId("uncheckedTxn" + i))
                                    .payingWith(GENESIS),
                            sleepFor(125L * Math.min(1, 19 - i))
                        })
                        .flatMap(Arrays::stream)
                        .toArray(HapiSpecOperation[]::new)),
                cryptoTransfer(HapiCryptoTransfer.tinyBarsFromTo(CIVILIAN_ACCOUNT, SECOND_ACCOUNT, 5L))
                        .payingWith(FEE_MONITOR_ACCOUNT)
                        .fee(ONE_HUNDRED_HBARS)
                        .via("pricyCall"),
                getTxnRecord("pricyCall").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                    log.info("Congestion fee is {}", congestionFee);
                    sevenXPrice.set(congestionFee);
                }),
                withOpContext((spec, opLog) -> Assertions.assertEquals(
                        7.0,
                        (1.0 * sevenXPrice.get()) / normalPrice.get(),
                        0.1,
                        "~7x multiplier should be in affect!")));
    }
}
