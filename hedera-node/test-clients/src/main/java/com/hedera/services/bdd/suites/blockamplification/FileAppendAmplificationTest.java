// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.APPEND_CHUNK_BYTES;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.growFileWithExemptAppends;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.payload;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>FileAppend</b> block-stream amplification vector.
 *
 * <p>Under PR #27718 a {@code FileAppend} still re-emits the <em>whole</em> file as a state change, because the changed
 * {@code contents} field is itself the large field (the partial-diff logic only helps when a few top-level fields
 * change). So once an attacker has grown a file to N bytes, every subsequent one-transaction append — costing only the
 * node/network fee for its small payload — pushes another N bytes into the block. A short run of such appends crosses
 * the execution budget, after which the block is full and further non-exempt transactions are rejected with
 * {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p>Two proofs are provided: one over the normal mainnet {@code FileAppend} lane (13 ops/s) and one over the HIP-1313
 * high-volume {@code FileAppend} lane (50,000 ops/s), which lets the same amplifying append be sustained at a vastly
 * higher rate for the same per-operation price.
 *
 * <p>Measured footprint of a single append with deltas on (see {@code .context} reports): a 1 MiB file re-emits ~1.05 MB
 * per append. Here the file is kept small (~{@value #FILE_TARGET_BYTES} bytes) so the one-node network stays fast while
 * still crossing the deliberately small {@value #BUDGET_BYTES}-byte budget in a single append.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class FileAppendAmplificationTest {
    private static final int BUDGET_BYTES = 49_152; // 48 KiB
    private static final int FILE_TARGET_BYTES = 57_344; // 56 KiB, so one re-emit alone crosses the budget
    private static final String ATTACK_FILE = "amplificationFile";
    private static final int ATTACK_ATTEMPTS = 8;

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
                            "blockStream.maxBlockExecutionSizeBytes", "49152",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> fileAppendFloodsBlockOnNormalLane() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                fileCreate(ATTACK_FILE).contents(payload(APPEND_CHUNK_BYTES)).payingWith(GENESIS),
                growFileWithExemptAppends(ATTACK_FILE, FILE_TARGET_BYTES),
                // Move into a fresh block (the budget resets at block start) before the attacker acts
                sleepForSeconds(11),
                floodAssertingBudgetRejection("fileAppend-normal", ATTACK_ATTEMPTS, () -> fileAppend(ATTACK_FILE)
                        .content(payload(APPEND_CHUNK_BYTES))
                        .payingWith(ATTACKER)
                        .noLogging()));
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
                            "blockStream.maxBlockExecutionSizeBytes", "49152",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> fileAppendFloodsBlockOnHighVolumeLane() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                fileCreate(ATTACK_FILE).contents(payload(APPEND_CHUNK_BYTES)).payingWith(GENESIS),
                growFileWithExemptAppends(ATTACK_FILE, FILE_TARGET_BYTES),
                sleepForSeconds(11),
                floodAssertingBudgetRejection("fileAppend-highVolume", ATTACK_ATTEMPTS, () -> fileAppend(ATTACK_FILE)
                        .content(payload(APPEND_CHUNK_BYTES))
                        .payingWith(ATTACKER)
                        .withHighVolume()
                        .noLogging()));
    }
}
