// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.contract.opsduration;

import static com.hedera.services.bdd.junit.TestTags.SMART_CONTRACT;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.*;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.*;
import static com.hedera.services.bdd.suites.HapiSuite.*;
import static com.hedera.services.bdd.suites.contract.Utils.asAddress;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.transactions.contract.HapiParserUtil;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenType;
import java.math.BigInteger;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;

@Tag(SMART_CONTRACT)
@DisplayName("opsDurationThrottle")
@OrderedInIsolation
public class OpsDurationThrottleTest {
    private static final String OPS_DURATION_THROTTLE = "OpsDurationThrottle";
    private static final String SYSTEM_CONTRACT_TRANSFER = "HtsTransferFrom";
    private static final String SENDER = "sender";
    private static final String RECEIVER = "receiver";
    private static final String TOKEN = "token";
    private static final String THROTTLE_THROTTLE_BY_OPS_DURATION = "contracts.throttle.throttleByOpsDuration";
    private static final String OPS_DURATION_THROTTLE_CAPACITY = "contracts.opsDurationThrottleCapacity";
    private static final String OPS_DURATION_THROTTLE_UNITS_FREED_PER_SECOND =
            "contracts.opsDurationThrottleUnitsFreedPerSecond";

    private static final long DEFAULT_OPS_DURATION_CAPACITY = 10000000;

    private SpecOperation enableDefaultOpsDurationThrottleNoRefill() {
        return overridingAllOf(Map.of(
                THROTTLE_THROTTLE_BY_OPS_DURATION, Boolean.toString(true),
                OPS_DURATION_THROTTLE_CAPACITY, Long.toString(DEFAULT_OPS_DURATION_CAPACITY),
                OPS_DURATION_THROTTLE_UNITS_FREED_PER_SECOND, Long.toString(0)));
    }

    private SpecOperation disableOpsDurationThrottle() {
        return overriding(THROTTLE_THROTTLE_BY_OPS_DURATION, Boolean.toString(false));
    }

    private void restoreDefaults(HapiSpec spec) {
        allRunFor(
                spec,
                restoreDefault(THROTTLE_THROTTLE_BY_OPS_DURATION),
                restoreDefault(OPS_DURATION_THROTTLE_CAPACITY),
                restoreDefault(OPS_DURATION_THROTTLE_UNITS_FREED_PER_SECOND));
    }

    @HapiTest
    @Order(1)
    @DisplayName("ops duration throttle can overfill but does not exceed a reasonable threshold")
    public Stream<DynamicTest> overfillOpsDuration() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 450)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "run")
                                            .gas(200_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS,
                                                    ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // We expect the throttle to overfill, but not exceed 1.5x the capacity
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 100.0, 150.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(2)
    @DisplayName("call function to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedOpsDuration() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(spec, contractCall(OPS_DURATION_THROTTLE, "run").gas(200_000L));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // This scenario shouldn't consume more than 20% of the throttle
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 0.0, 20.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(3)
    @DisplayName("call system contract to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedDurationThrottleWithSystemContract() {
        return hapiTest(
                disableOpsDurationThrottle(),
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
                enableDefaultOpsDurationThrottleNoRefill(),
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
                                                    ResponseCodeEnum.SUCCESS,
                                                    ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // We expect the throttle to overfill, but not exceed 1.5x the capacity
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 100.0, 150.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(4)
    @DisplayName("call system contract that won't exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedDurationThrottleWithSystemContract() {
        return hapiTest(
                disableOpsDurationThrottle(),
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
                enableDefaultOpsDurationThrottleNoRefill(),
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
                                            .gas(200_000L)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // This scenario shouldn't consume more than 20% of the throttle
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 0.0, 20.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(5)
    @DisplayName("call create opcode to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedThrottleWithOpCode() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 2000)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "opsRun")
                                            .gas(400_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.SUCCESS,
                                                    ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // We expect the throttle to overfill, but not exceed 1.5x the capacity
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 100.0, 150.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(6)
    @DisplayName("call create opcode fewer times to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedThrottleWithOpCode() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 10)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "opsRun")
                                            .gas(400_000L)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // This scenario shouldn't consume more than 20% of the throttle
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 0.0, 20.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(7)
    @DisplayName("call create opcode with expected revert to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedThrottleWithOpCodeReverts() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 2000)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "opsRunRevert")
                                            .gas(400_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.CONTRACT_REVERT_EXECUTED,
                                                    ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // We expect the throttle to overfill, but not exceed 1.5x the capacity
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 100.0, 150.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(8)
    @DisplayName("call create opcode fewer times to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedThrottleWithOpCodeReverts() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 10)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "opsRunRevert")
                                            .gas(400_000L)
                                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // This scenario shouldn't consume more than 20% of the throttle
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 0.0, 20.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(9)
    @DisplayName("call create opcode with expected halt to exceed ops duration throttle")
    public Stream<DynamicTest> doExceedThrottleWithOpCodeHalts() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 2000)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "opsRunHalt")
                                            .gas(400_000L)
                                            .hasKnownStatusFrom(
                                                    ResponseCodeEnum.CONTRACT_REVERT_EXECUTED,
                                                    ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // We expect the throttle to overfill, but not exceed 1.5x the capacity
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 100.0, 150.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(10)
    @DisplayName("call create opcode fewer times to not exceed ops duration throttle")
    public Stream<DynamicTest> doNotExceedThrottleWithOpCodeHalts() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            inParallel(IntStream.range(0, 10)
                                    .mapToObj(i -> sourcing(() -> contractCall(OPS_DURATION_THROTTLE, "opsRunHalt")
                                            .gas(400_000L)
                                            .hasKnownStatus(ResponseCodeEnum.CONTRACT_REVERT_EXECUTED)))
                                    .toArray(HapiSpecOperation[]::new)));
                    // Let the metrics update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // This scenario shouldn't consume more than 20% of the throttle
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 0.0, 20.0));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(11)
    @DisplayName("call nested function to exceed ops duration throttle")
    public Stream<DynamicTest> nestedExceedOpsDuration() {
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                // Let's add some initial capacity, but don't free any units once exhausted
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            // First a success that doesn't overflow the ops duration limit
                            contractCall(OPS_DURATION_THROTTLE, "runMulti", BigInteger.valueOf(5L))
                                    .gas(10_000_000L)
                                    .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                            // This is expected to leave the bucket overfilled when complete
                            contractCall(OPS_DURATION_THROTTLE, "runMulti", BigInteger.valueOf(50))
                                    .gas(10_000_000L)
                                    .hasKnownStatus(ResponseCodeEnum.SUCCESS),
                            // The bucket is full, so this should fail (even though it's just a single iteration)
                            contractCall(OPS_DURATION_THROTTLE, "runMulti", BigInteger.valueOf(1))
                                    .gas(10_000_000L)
                                    .hasKnownStatus(ResponseCodeEnum.CONSENSUS_GAS_EXHAUSTED));
                    restoreDefaults(spec);
                }));
    }

    @HapiTest
    @Order(11)
    @DisplayName("account creation consumes ops duration")
    public Stream<DynamicTest> accountCreationConsumesOpsDuration() {
        final var payer = "payer";
        return hapiTest(
                disableOpsDurationThrottle(),
                uploadInitCode(OPS_DURATION_THROTTLE),
                contractCreate(OPS_DURATION_THROTTLE).gas(2_000_000L),
                cryptoCreate(payer).balance(ONE_HUNDRED_HBARS),
                enableDefaultOpsDurationThrottleNoRefill(),
                withOpContext((spec, opLog) -> {
                    allRunFor(
                            spec,
                            // This call is going to make 25 transfers to subsequent accounts starting at address 10^48
                            // (chosen arbitrarily)
                            contractCall(
                                            OPS_DURATION_THROTTLE,
                                            "createNAccounts",
                                            BigInteger.TEN.pow(48),
                                            BigInteger.valueOf(25L))
                                    .payingWith(payer)
                                    .signedBy(payer)
                                    .sending(ONE_HBAR)
                                    .gas(15_000_000L)
                                    .hasKnownStatus(ResponseCodeEnum.SUCCESS));
                    // Let's wait for the metrics to update
                    allRunFor(spec, sleepForSeconds(3));
                    final double throttlePercentUsed = getOpsDurationThrottlePercentUsed(spec);
                    // We expect the throttle to be significantly overfilled with our test limits
                    allRunFor(spec, valueIsInRange(throttlePercentUsed, 1000.0, 6000.0));
                }));
    }
}
