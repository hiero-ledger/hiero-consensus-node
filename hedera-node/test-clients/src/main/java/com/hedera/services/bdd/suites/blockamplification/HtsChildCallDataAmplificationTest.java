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
 * Proof of concept for the <b>HTS/HAS/HSS child-record {@code call_data}</b> amplification vector (bug analysis A4).
 *
 * <p>Every call into the native HTS system contract ({@code 0x167}) writes a reversible child record that copies the
 * call input into {@code call_data} and the output into {@code result_data}. That child record is
 * {@code TransactionOutput}, so PR #27718 never diffs it. Input is capped at {@code hedera.transaction.maxBytes}
 * (6,144 bytes) per call, and up to {@code consensus.handle.maxFollowingRecords} (50) calls can run per transaction, so
 * one contract call is worth up to ~307 KB of block output (see {@code .context} reports) — reconstructable data that
 * never needs to be in the stream.
 *
 * <p>This proof deploys a contract whose runtime makes {@value #CALLS} unrolled {@code CALL}s to {@code 0x167}, each
 * passing {@value #ARGS_BYTES} bytes of input, so one contract call externalizes ~{@value #CALLS}×6 KiB of copied
 * {@code call_data} and crosses the {@value #BUDGET_BYTES}-byte budget; the next call is rejected
 * {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p><b>High-volume note:</b> reached through {@code ContractCall}, which has no HIP-1313 high-volume lane.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class HtsChildCallDataAmplificationTest {
    private static final int BUDGET_BYTES = 32_768; // 32 KiB
    private static final int HTS_ADDRESS = 0x167;
    private static final int ARGS_BYTES = 6_000; // just under hedera.transaction.maxBytes = 6144
    private static final int CALLS = 10; // ~60 KiB of copied call_data per contract call
    private static final String HTS_CALLER = "htsCaller";
    private static final int ATTACK_ATTEMPTS = 6;

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
                            "blockStream.maxBlockExecutionSizeBytes", "32768",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> htsChildRecordsFloodBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                contractCreate(HTS_CALLER)
                        .inlineInitCode(initcodeReturning(runtimeCallingPrecompile(HTS_ADDRESS, ARGS_BYTES, CALLS)))
                        .gas(300_000)
                        .payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "htsCallData",
                        ATTACK_ATTEMPTS,
                        () -> contractCall(HTS_CALLER)
                                .gas(8_000_000)
                                .payingWith(ATTACKER)
                                .noLogging(),
                        INSUFFICIENT_GAS,
                        CONTRACT_REVERT_EXECUTED));
    }
}
