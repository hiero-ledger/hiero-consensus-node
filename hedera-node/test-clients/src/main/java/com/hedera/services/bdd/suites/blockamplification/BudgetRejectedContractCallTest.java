// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.initcodeReturning;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.word;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_REVERT_EXECUTED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.THROTTLED_AT_CONSENSUS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>budget-rejected {@code ContractCall} is free</b> deterrent-gap vector (bug analysis A5).
 *
 * <p>When the execution budget rejects a submitted transaction it charges "normal consensus throttle fees" — but a
 * {@code ContractCall}'s throttle fee is effectively zero (its simple-fee {@code baseFee} is 0 and it is
 * node/network-fee exempt; gas is only charged inside the handler, which never runs on rejection). So once a block is
 * full, an attacker can keep hammering it with {@code ContractCall}s that are all rejected
 * {@code THROTTLED_AT_CONSENSUS} for free, prolonging a full block at ~2.2 MB/s of rejected traffic at no cost — a
 * deterrent gap against the very protection the budget is supposed to provide. The mitigation evaluated is a minimum
 * bytes fee on budget rejection.
 *
 * <p>This proof fills a block with one large-return {@code ContractCall}, then floods further calls. It asserts not only
 * that calls are rejected {@code THROTTLED_AT_CONSENSUS}, but that each rejected call's fee is far below the fee of the
 * call that actually executed — i.e. rejection is essentially free.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions are made explicitly below
public class BudgetRejectedContractCallTest {
    private static final int BUDGET_BYTES = 65_536; // 64 KiB
    private static final int RETURN_BYTES = 262_144; // 256 KiB, so the first call crosses the budget
    private static final String RETURNER = "freeRejectReturner";
    private static final int ATTEMPTS = 6;

    // PUSH1 0x00 ; CALLDATALOAD ; PUSH1 0x00 ; RETURN
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
    final Stream<DynamicTest> budgetRejectedContractCallsAreEffectivelyFree() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                contractCreate(RETURNER)
                        .inlineInitCode(initcodeReturning(RETURN_RUNTIME))
                        .gas(200_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                withOpContext((spec, opLog) -> {
                    final List<String> vias = new ArrayList<>();
                    for (int i = 0; i < ATTEMPTS; i++) {
                        final String via = "freeReject-" + i;
                        vias.add(via);
                        allRunFor(
                                spec,
                                contractCall(RETURNER)
                                        .withExplicitRawParams(word(RETURN_BYTES))
                                        .gas(3_000_000)
                                        .payingWith(ATTACKER)
                                        .via(via)
                                        .noLogging()
                                        .hasKnownStatusFrom(
                                                SUCCESS,
                                                INSUFFICIENT_GAS,
                                                CONTRACT_REVERT_EXECUTED,
                                                THROTTLED_AT_CONSENSUS));
                    }
                    final AtomicLong maxExecutedFee = new AtomicLong(0);
                    final AtomicLong maxRejectedFee = new AtomicLong(0);
                    int executed = 0;
                    int rejected = 0;
                    for (final String via : vias) {
                        final AtomicReference<ResponseCodeEnum> status = new AtomicReference<>();
                        final AtomicLong fee = new AtomicLong();
                        allRunFor(spec, getTxnRecord(via).noLogging().exposingTo(record -> {
                            status.set(record.getReceipt().getStatus());
                            fee.set(record.getTransactionFee());
                        }));
                        if (status.get() == THROTTLED_AT_CONSENSUS) {
                            rejected++;
                            maxRejectedFee.updateAndGet(prev -> Math.max(prev, fee.get()));
                        } else {
                            executed++;
                            maxExecutedFee.updateAndGet(prev -> Math.max(prev, fee.get()));
                        }
                    }
                    opLog.info(
                            "[AMPLIFICATION] budget-rejected ContractCall: {} executed (max fee {} tinybar), {} rejected "
                                    + "(max fee {} tinybar)",
                            executed,
                            maxExecutedFee.get(),
                            rejected,
                            maxRejectedFee.get());
                    assertTrue(rejected > 0, "Expected at least one ContractCall rejected THROTTLED_AT_CONSENSUS");
                    assertTrue(executed > 0, "Expected at least one ContractCall to execute and pay real gas");
                    assertTrue(
                            maxRejectedFee.get() * 10 < maxExecutedFee.get(),
                            "Expected a budget-rejected ContractCall to be effectively free, but the rejected fee "
                                    + maxRejectedFee.get() + " tinybar was not far below the executed fee "
                                    + maxExecutedFee.get() + " tinybar");
                }));
    }
}
