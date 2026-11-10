// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.atomicBatch;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
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
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.REVERTED_SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>AtomicBatch</b> block-stream amplification vector.
 *
 * <p>The execution budget is checked once per submitted <em>parent</em> transaction (in
 * {@code HandleWorkflow#executeSubmittedParent}). An {@code AtomicBatch} is a single parent that runs up to
 * {@code atomicBatch.maxNumberOfTransactions} (50 on mainnet) inner transactions to completion. So one batch can pack
 * many amplifying inner transactions whose combined output dwarfs the budget, and all of them are emitted in full
 * because the gate is only consulted once, at the batch boundary — <em>then</em> the block is full and the next parent is
 * rejected with {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p>This proof floods batches that each wrap a contract call returning {@value #RETURN_BYTES} bytes, so a single batch
 * — one parent, one budget check — emits half a megabyte of result data. The first batch crosses the
 * {@value #BUDGET_BYTES}-byte budget; later batches are rejected whole, without running any inner transaction. On
 * mainnet a batch may carry up to {@code atomicBatch.maxNumberOfTransactions=50} inner transactions, so the real
 * worst case is up to 50× this per parent — all admitted on the single gate check, because the budget is not re-checked
 * between inner transactions.
 *
 * <p>The mitigation evaluated in the analysis is to apply the per-transaction output cap to the whole batch and to
 * re-check the budget between inner transactions, so a batch cannot be used to bypass the per-parent gate.
 *
 * <p><b>High-volume note:</b> {@code AtomicBatch} is not a HIP-1313 high-volume functionality, so there is no
 * high-volume lane for the batch itself; the amplification comes from the inner fan-out, not the throttle rate.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class AtomicBatchAmplificationTest {
    private static final int BUDGET_BYTES = 65_536; // 64 KiB
    private static final int RETURN_BYTES = 524_288; // 512 KiB returned by the batch's inner call
    private static final String RETURNER = "batchBigReturner";
    private static final String BATCH_OPERATOR = "batchOperator";
    private static final int ATTACK_ATTEMPTS = 4;

    // PUSH1 0x00 ; CALLDATALOAD ; PUSH1 0x00 ; RETURN  -> returns calldata[0] zero bytes
    private static final byte[] RETURN_RUNTIME = {0x60, 0x00, 0x35, 0x60, 0x00, (byte) 0xf3};

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
    final Stream<DynamicTest> atomicBatchPacksAmplifiersPastTheGate() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                newKeyNamed(BATCH_OPERATOR),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS).key(BATCH_OPERATOR),
                contractCreate(RETURNER)
                        .inlineInitCode(initcodeReturning(RETURN_RUNTIME))
                        .gas(200_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "atomicBatch",
                        ATTACK_ATTEMPTS,
                        i -> atomicBatch(contractCall(RETURNER)
                                        .withExplicitRawParams(word(RETURN_BYTES))
                                        .gas(6_000_000)
                                        .payingWith(ATTACKER)
                                        .batchKey(BATCH_OPERATOR)
                                        // The inner's output is written to the block whatever its own status; when the
                                        // batch is budget-rejected the inner reverts instead of running
                                        .hasKnownStatusFrom(
                                                SUCCESS, INSUFFICIENT_GAS, CONTRACT_REVERT_EXECUTED, REVERTED_SUCCESS))
                                .payingWith(ATTACKER)
                                .signedByPayerAnd(BATCH_OPERATOR)
                                .noLogging(),
                        // A batch whose inner calls run out of gas still emits their output and still consumes the
                        // batch
                        INNER_TRANSACTION_FAILED));
    }
}
