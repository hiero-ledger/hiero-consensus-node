// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opsduration;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoApproveAllowance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uploadInitCode;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.burstIncreasesThroughputBy;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.restoreDefault;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.throttleUsagePercentageLessThreshold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.throttleUsagePercentageMoreThanThreshold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenType;
import com.swirlds.metrics.impl.AtomicDouble;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;

@Tag(SMART_CONTRACT)
@DisplayName("opsDurationThrottle")
@HapiTestLifecycle
@OrderedInIsolation
public class OpsDurationThrottleTest {
    private static final String OPS_DURATION_COUNTER = "OpsDurationThrottle";
    private static final String SYSTEM_CONTRACT_TRANSFER = "HtsTransferFrom";
    private static final String SENDER = "sender";
    private static final String RECEIVER = "receiver";
    private static final String TOKEN = "token";
    protected static final String THROTTLE_THROTTLE_BY_OPS_DURATION = "contracts.throttle.throttleByOpsDuration";
    protected static final String OPS_DURATION_COUNTER_BURST_SECONDS = "contracts.opsDurationThrottleBurstSeconds";
    protected static final String MAX_OPS_DURATION = "contracts.maxOpsDuration";
    protected static final String DURATION_PERIOD = "10000";
    protected static final String SMALLER_DURATION_PERIOD = "10";
    protected static final String RUN_MULTI_DURATION_PERIOD = "10000000";

    @AfterEach
    public void restABit() {
        sleepForSeconds(2);
    }

    @AfterAll
    public static void restoreConfig() {
        restoreDefault(THROTTLE_THROTTLE_BY_OPS_DURATION);
    }

    @HapiTest
    @Order(1)
    @DisplayName("call function to exceed ops duration throttle")
    public Stream<DynamicTest> exceedOpsDuration() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(MAX_OPS_DURATION, DURATION_PERIOD),
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 450)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "run")
                                            .gas(200_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageMoreThanThreshold(duration.get(), 95.0));
                }),
                restoreDefault(MAX_OPS_DURATION));
    }

    @HapiTest
    @Order(2)
    @DisplayName("call function to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedOpsDuration() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 5)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "run")
                                            .gas(200_000L)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageLessThreshold(duration.get(), 20.0));
                }));
    }

    @HapiTest
    @Order(3)
    @DisplayName("call system contract to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedDurationThrottleWithSystemContract() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                cryptoCreate(SENDER).balance(1_000_000_000L),
                cryptoCreate(RECEIVER).balance(1_000_000_000L),
                tokenCreate(TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000_000L)
                        .treasury(SENDER),
                tokenAssociate(RECEIVER, TOKEN),
                uploadInitCode(SYSTEM_CONTRACT_TRANSFER),
                contractCreate(SYSTEM_CONTRACT_TRANSFER).gas(2_000_000L),
                cryptoApproveAllowance()
                        .addTokenAllowance(SENDER, TOKEN, SYSTEM_CONTRACT_TRANSFER, 1_000_000L)
                        .signedByPayerAnd(SENDER),
                overriding(MAX_OPS_DURATION, SMALLER_DURATION_PERIOD),
                withOpContext((spec, opLog) -> {
                    final var tokenAddress = HapiParserUtil.asHeadlongAddress(
                            asAddress(spec.registry().getTokenID(TOKEN)));
                    final var senderAddress = HapiParserUtil.asHeadlongAddress(
                            asAddress(spec.registry().getAccountID(SENDER)));
                    final var receiverAddress = HapiParserUtil.asHeadlongAddress(
                            asAddress(spec.registry().getAccountID(RECEIVER)));
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 600)
                                    .mapToObj(i -> sourcing(() -> contractCall(
                                                    SYSTEM_CONTRACT_TRANSFER,
                                                    "htsTransferFrom",
                                                    tokenAddress,
                                                    senderAddress,
                                                    receiverAddress,
                                                    BigInteger.ONE)
                                            .gas(200_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageMoreThanThreshold(duration.get(), 98.0));
                }),
                restoreDefault(MAX_OPS_DURATION));
    }

    @HapiTest
    @Order(4)
    @DisplayName("call system contract that won't exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedDurationThrottleWithSystemContract() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                overriding(MAX_OPS_DURATION, DURATION_PERIOD),
                cryptoCreate(SENDER).balance(1_000_000_000L),
                cryptoCreate(RECEIVER).balance(1_000_000_000L),
                tokenCreate(TOKEN)
                        .tokenType(TokenType.FUNGIBLE_COMMON)
                        .initialSupply(1_000_000L)
                        .treasury(SENDER),
                tokenAssociate(RECEIVER, TOKEN),
                uploadInitCode(SYSTEM_CONTRACT_TRANSFER),
                contractCreate(SYSTEM_CONTRACT_TRANSFER).gas(2_000_000L),
                cryptoApproveAllowance()
                        .addTokenAllowance(SENDER, TOKEN, SYSTEM_CONTRACT_TRANSFER, 1_000_000L)
                        .signedByPayerAnd(SENDER),
                withOpContext((spec, opLog) -> {
                    final var tokenAddress = HapiParserUtil.asHeadlongAddress(
                            asAddress(spec.registry().getTokenID(TOKEN)));
                    final var senderAddress = HapiParserUtil.asHeadlongAddress(
                            asAddress(spec.registry().getAccountID(SENDER)));
                    final var receiverAddress = HapiParserUtil.asHeadlongAddress(
                            asAddress(spec.registry().getAccountID(RECEIVER)));
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 5)
                                    .mapToObj(i -> sourcing(() -> contractCall(
                                                    SYSTEM_CONTRACT_TRANSFER,
                                                    "htsTransferFrom",
                                                    tokenAddress,
                                                    senderAddress,
                                                    receiverAddress,
                                                    BigInteger.ONE)
                                            .gas(200_000L)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageLessThreshold(duration.get(), 20.0));
                }),
                restoreDefault(MAX_OPS_DURATION));
    }

    @HapiTest
    @Order(5)
    @DisplayName("call create opcode to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedThrottleWithOpCode() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                overriding(MAX_OPS_DURATION, RUN_MULTI_DURATION_PERIOD),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 650)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "opsRun")
                                            .gas(400_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageMoreThanThreshold(duration.get(), 95.0));
                }),
                restoreDefault(MAX_OPS_DURATION));
    }

    @HapiTest
    @Order(6)
    @DisplayName("call create opcode fewer times to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedThrottleWithOpCode() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 400)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "opsRun")
                                            .gas(400_000L)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageLessThreshold(duration.get(), 10.0));
                }));
    }

    @HapiTest
    @Order(7)
    @DisplayName("call create opcode with expected revert to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedThrottleWithOpCodeReverts() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                overriding(MAX_OPS_DURATION, DURATION_PERIOD),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 400)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "opsRunRevert")
                                            .gas(400_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.CONTRACT_REVERT_EXECUTED,
                                                    ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageMoreThanThreshold(duration.get(), 98.0));
                }),
                restoreDefault(MAX_OPS_DURATION));
    }

    @HapiTest
    @Order(8)
    @DisplayName("call create opcode fewer times to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedThrottleWithOpCodeReverts() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 400)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "opsRunRevert")
                                            .gas(400_000L)
                                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageLessThreshold(duration.get(), 10.0));
                }));
    }

    @HapiTest
    @Order(9)
    @DisplayName("call create opcode with expected halt to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedThrottleWithOpCodeHalts() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                overriding(MAX_OPS_DURATION, DURATION_PERIOD),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 400)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "opsRunHalt")
                                            .gas(400_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.CONTRACT_REVERT_EXECUTED,
                                                    ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageMoreThanThreshold(duration.get(), 95.0));
                }),
                restoreDefault(MAX_OPS_DURATION));
    }

    @HapiTest
    @Order(10)
    @DisplayName("call create opcode fewer times to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedThrottleWithOpCodeHalts() {
        final AtomicDouble duration = new AtomicDouble(0.0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 400)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "opsRunHalt")
                                            .gas(400_000L)
                                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)
                                            .collectMaxOpsDuration(duration)))
                                    .toArray(HapiSpecOperation[]::new)));
                    allRunFor(spec, throttleUsagePercentageLessThreshold(duration.get(), 10.0));
                }));
    }

    @HapiTest
    @Order(11)
    @DisplayName("compare number of successful calls before and after updating the burst size")
    public Stream<DynamicTest> burstTest() {
        final AtomicLong preSuccessCounter = new AtomicLong(0);
        final AtomicLong postSuccessCounter = new AtomicLong(0);
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 800)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "run")
                                            .gas(200_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .countingSuccessfulTransactionTo(preSuccessCounter)))
                                    .toArray(HapiSpecOperation[]::new)));
                    overriding(OPS_DURATION_COUNTER_BURST_SECONDS, "2");
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 800)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_COUNTER, "run")
                                            .gas(200_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS, ResponseCodeEnum.THROTTLED_AT_CONSENSUS)
                                            .countingSuccessfulTransactionTo(postSuccessCounter)))
                                    .toArray(HapiSpecOperation[]::new)));
                    burstIncreasesThroughputBy(preSuccessCounter.get(), postSuccessCounter.get(), 200L);
                }),
                restoreDefault(OPS_DURATION_COUNTER_BURST_SECONDS));
    }

    @HapiTest
    @Order(12)
    @DisplayName("call nested function to exceed ops duration throttle")
    public Stream<DynamicTest> nestedExceedOpsDuration() {
        return hapiTest(
                overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, "true"),
                uploadInitCode(OPS_DURATION_COUNTER),
                contractCreate(OPS_DURATION_COUNTER).gas(2_000_000L),
                overriding(MAX_OPS_DURATION, RUN_MULTI_DURATION_PERIOD),
                withOpContext((spec, opLog) -> allRunFor(
                        spec,
                        contractCall(OPS_DURATION_COUNTER, "runMulti", BigInteger.valueOf(5L))
                                .gas(10_000_000L)
                                .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                        contractCall(OPS_DURATION_COUNTER, "runMulti", BigInteger.valueOf(1000000000))
                                .gas(10_000_000L)
                                .hasKnownStatus(ResponseCodeEnum.THROTTLED_AT_CONSENSUS))),
                restoreDefault(OPS_DURATION_COUNTER_BURST_SECONDS),
                restoreDefault(MAX_OPS_DURATION));
    }
}
