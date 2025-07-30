// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.integration;

import static com.hedera.services.bdd.junit.RepeatableReason.NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION;
import static com.hedera.services.bdd.junit.TestTags.INTEGRATION;
import static com.hedera.services.bdd.junit.hedera.embedded.EmbeddedMode.REPEATABLE;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.resourceAsString;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
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
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
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

@Order(-1)
@Tag(INTEGRATION)
@TargetEmbeddedMode(REPEATABLE)
public class CongestionPricingTest {
    private static final Logger log = LogManager.getLogger(CongestionPricingTest.class);

    private static final String CIVILIAN_ACCOUNT = "civilian";

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"contracts.maxGasPerSec", "fees.percentCongestionMultipliers", "fees.minCongestionPeriod"})
    Stream<DynamicTest> canUpdateGasThrottleMultipliersDynamically() {
        final var contract = "Multipurpose";

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
                            log.info("Normal ContractCall fee is {}", normalFee);
                            normalPrice.set(normalFee);
                        })
                        .logged(),
                overridingTwo("fees.percentCongestionMultipliers", "1,7x", "fees.minCongestionPeriod", "1"),
                new SysFileOverrideOp(
                        THROTTLES, () -> resourceAsString("testSystemFiles/artificial-limits-congestion.json")),
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
                    log.info("Congestion ContractCall fee is {}", congestionFee);
                    sevenXPrice.set(congestionFee);
                }),
                withOpContext((spec, opLog) -> Assertions.assertEquals(
                        7.0,
                        (1.0 * sevenXPrice.get()) / normalPrice.get(),
                        0.1,
                        "~7x multiplier should be in effect")));
    }

    @LeakyRepeatableHapiTest(
            value = {NEEDS_VIRTUAL_TIME_FOR_FAST_EXECUTION},
            overrides = {"fees.percentCongestionMultipliers", "fees.minCongestionPeriod"})
    Stream<DynamicTest> canUpdateTransferThrottleMultipliersDynamically() {
        AtomicLong normalPrice = new AtomicLong();
        AtomicLong sevenXPrice = new AtomicLong();

        return hapiTest(
                cryptoCreate(CIVILIAN_ACCOUNT).payingWith(GENESIS).balance(ONE_MILLION_HBARS),
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("normalTransfer"),
                getTxnRecord("normalTransfer")
                        .providingFeeTo(normalFee -> {
                            log.info("Normal fee for transfer is {}", normalFee);
                            normalPrice.set(normalFee);
                        })
                        .logged(),
                overridingTwo("fees.percentCongestionMultipliers", "1,7x", "fees.minCongestionPeriod", "1"),
                new SysFileOverrideOp(THROTTLES, () -> resourceAsString("testSystemFiles/extreme-limits.json")),
                sleepFor(2_000),
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
                cryptoTransfer(tinyBarsFromTo(CIVILIAN_ACCOUNT, FUNDING, 5L))
                        .fee(ONE_HUNDRED_HBARS)
                        .payingWith(CIVILIAN_ACCOUNT)
                        .via("congestedTransfer"),
                getTxnRecord("congestedTransfer").payingWith(GENESIS).providingFeeTo(congestionFee -> {
                    log.info("Congestion fee for transfer is {}", congestionFee);
                    sevenXPrice.set(congestionFee);
                }),
                withOpContext((spec, opLog) -> Assertions.assertEquals(
                        7.0,
                        (1.0 * sevenXPrice.get()) / normalPrice.get(),
                        0.1,
                        "~7x multiplier should be in effect")));
    }
}
