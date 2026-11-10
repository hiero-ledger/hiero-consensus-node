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
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.runtimeCallingPrecompile;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>PRNG system contract</b> block-stream amplification vector (bug analysis A2).
 *
 * <p>The PRNG system contract at {@code 0x169} copies the caller's full input into the {@code call_data} of a child
 * record for a fixed gas price. Unlike the other native system contracts it extends {@code AbstractFullContract} and so
 * does <em>not</em> inherit their {@code hedera.transaction.maxBytes} input cap, so the copied input is unbounded by the
 * input-size guard, and up to {@code consensus.handle.maxFollowingRecords} (50) child records can be produced per
 * transaction. That makes one contract call worth up to ~135 MB of block output in the worst case (see {@code .context}
 * reports) — and the child records are {@code TransactionOutput}, which PR #27718 never touches.
 *
 * <p>This proof deploys a contract whose runtime makes {@value #CALLS} unrolled {@code CALL}s to {@code 0x169}, each
 * passing {@value #ARGS_BYTES} bytes of memory, so one contract call externalizes ~{@value #CALLS}×16 KiB of copied
 * input and crosses the {@value #BUDGET_BYTES}-byte budget immediately; the next call is rejected
 * {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p>The fix evaluated in the analysis (patch 03) bounds the recorded input to {@code transaction.maxBytes}, then drops
 * {@code call_data} from the record entirely; this branch is the unmitigated "before".
 *
 * <p><b>High-volume note:</b> the PRNG is reached through {@code ContractCall}, which has no HIP-1313 high-volume lane,
 * so there is no high-volume variant; a single call already dwarfs the budget.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class PrngAmplificationTest {
    private static final int BUDGET_BYTES = 65_536; // 64 KiB
    private static final int PRNG_ADDRESS = 0x169;
    private static final int ARGS_BYTES = 16_384; // 16 KiB of input copied per inner CALL
    private static final int CALLS = 10; // 10 child records => ~160 KiB per contract call
    private static final String PRNG_CALLER = "prngCaller";
    private static final int ATTACK_ATTEMPTS = 4;

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
    final Stream<DynamicTest> prngChildRecordsFloodBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                contractCreate(PRNG_CALLER)
                        .inlineInitCode(initcodeReturning(runtimeCallingPrecompile(PRNG_ADDRESS, ARGS_BYTES, CALLS)))
                        .gas(300_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "prng",
                        ATTACK_ATTEMPTS,
                        () -> contractCall(PRNG_CALLER)
                                .gas(8_000_000)
                                .payingWith(ATTACKER)
                                .noLogging(),
                        INSUFFICIENT_GAS,
                        CONTRACT_REVERT_EXECUTED));
    }
}
