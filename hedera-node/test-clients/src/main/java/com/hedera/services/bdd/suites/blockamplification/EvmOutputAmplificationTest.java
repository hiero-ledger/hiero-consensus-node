// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.initcodeReturning;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.word;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>EVM contract output</b> block-stream amplification vector.
 *
 * <p>A contract can return (or revert with) an arbitrarily large byte array. That array is externalized in the
 * transaction's {@code EvmTransactionResult} ({@code result_data} on success, {@code error_message} on revert), which
 * lives in {@code TransactionOutput} and is <em>never</em> touched by PR #27718 (it is output, not state) and is not
 * bounded by the 256 KiB {@code contracts.maxSerializedTraceDataBytes} trace limiter (which only counts contract actions
 * and slot usages). So one contract call can push hundreds of kilobytes — up to megabytes — into the block for the price
 * of its gas. Measured with deltas on (see {@code .context} reports): a 512 KiB return emits ~524 KB; a 512 KiB revert
 * reason emits ~1.05 MB even though the call fails {@code INSUFFICIENT_GAS}.
 *
 * <p>This proof deploys a 6-byte runtime contract whose body is {@code PUSH1 0; CALLDATALOAD; PUSH1 0; RETURN} — it
 * returns the number of zero bytes named by its first call argument — and then floods calls that each return
 * {@value #RETURN_BYTES} bytes, crossing the {@value #BUDGET_BYTES}-byte budget on the first call. Every later call in
 * the same block is rejected {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p><b>High-volume note:</b> {@code ContractCall} is <em>not</em> a HIP-1313 high-volume functionality (only
 * {@code ContractCreate} is — see {@code HighVolumePricingCalculator.HIGH_VOLUME_THROTTLE_FUNCTIONS}), so there is no
 * high-volume lane for this vector. The attacker is limited to the normal 350 ops/s {@code ContractCall} lane — yet a
 * single call already emits far more than a whole block budget, so the throttle rate is not the constraint.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class EvmOutputAmplificationTest {
    private static final int BUDGET_BYTES = 65_536; // 64 KiB
    private static final int RETURN_BYTES = 262_144; // 256 KiB returned/reverted/logged per call
    private static final String RETURNER = "bigReturner";
    private static final String REVERTER = "bigReverter";
    private static final String LOGGER = "bigLogger";
    private static final int ATTACK_ATTEMPTS = 4;

    // PUSH1 0x00 ; CALLDATALOAD ; PUSH1 0x00 ; RETURN  -> returns calldata[0] zero bytes from memory offset 0
    private static final byte[] RETURN_RUNTIME = {0x60, 0x00, 0x35, 0x60, 0x00, (byte) 0xf3};
    // ... ; REVERT -> reverts with calldata[0] zero bytes as the revert reason (-> hex error_message)
    private static final byte[] REVERT_RUNTIME = {0x60, 0x00, 0x35, 0x60, 0x00, (byte) 0xfd};
    // PUSH1 0 ; CALLDATALOAD ; PUSH1 0 ; LOG0 ; STOP -> emits a calldata[0]-byte LOG0 record
    private static final byte[] LOG_RUNTIME = {0x60, 0x00, 0x35, 0x60, 0x00, (byte) 0xa0, 0x00};

    @HapiTest
    @GenesisSubprocessTest(
            networkSize = 1,
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE",
                            "blockStream.enableStateChangeDeltas", "true",
                            "blockStream.stateChangeDeltaMinSaving", "16",
                            "blockStream.maxBlockExecutionSizeBytes", "65536",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> contractReturnDataFloodsBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                contractCreate(RETURNER)
                        .inlineInitCode(initcodeReturning(RETURN_RUNTIME))
                        .gas(200_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "evmReturn",
                        ATTACK_ATTEMPTS,
                        () -> contractCall(RETURNER)
                                .withExplicitRawParams(word(RETURN_BYTES))
                                .gas(3_000_000)
                                .payingWith(ATTACKER)
                                .noLogging(),
                        INSUFFICIENT_GAS,
                        CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    @GenesisSubprocessTest(
            networkSize = 1,
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE",
                            "blockStream.enableStateChangeDeltas", "true",
                            "blockStream.stateChangeDeltaMinSaving", "16",
                            "blockStream.maxBlockExecutionSizeBytes", "65536",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> contractRevertReasonFloodsBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                contractCreate(REVERTER)
                        .inlineInitCode(initcodeReturning(REVERT_RUNTIME))
                        .gas(200_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                // The revert reason survives the revert and is hex-doubled into error_message in TransactionOutput
                floodAssertingBudgetRejection(
                        "evmRevert",
                        ATTACK_ATTEMPTS,
                        () -> contractCall(REVERTER)
                                .withExplicitRawParams(word(RETURN_BYTES))
                                .gas(3_000_000)
                                .payingWith(ATTACKER)
                                .noLogging(),
                        INSUFFICIENT_GAS,
                        CONTRACT_REVERT_EXECUTED));
    }

    @HapiTest
    @GenesisSubprocessTest(
            networkSize = 1,
            subProcessNodeConfigs = {
                @SubProcessNodeConfig(
                        nodeId = 0,
                        applicationPropertiesOverrides = {
                            "blockStream.streamMode", "BLOCKS",
                            "blockStream.writerMode", "FILE",
                            "blockStream.enableStateChangeDeltas", "true",
                            "blockStream.stateChangeDeltaMinSaving", "16",
                            "blockStream.maxBlockExecutionSizeBytes", "65536",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> contractLogsFloodBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                contractCreate(LOGGER)
                        .inlineInitCode(initcodeReturning(LOG_RUNTIME))
                        .gas(200_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                // LOG data is carried in the EVM result and is not counted by the 256 KiB trace-data limiter
                floodAssertingBudgetRejection(
                        "evmLogs",
                        ATTACK_ATTEMPTS,
                        () -> contractCall(LOGGER)
                                .withExplicitRawParams(word(RETURN_BYTES))
                                .gas(6_000_000)
                                .payingWith(ATTACKER)
                                .noLogging(),
                        INSUFFICIENT_GAS,
                        CONTRACT_REVERT_EXECUTED));
    }
}
