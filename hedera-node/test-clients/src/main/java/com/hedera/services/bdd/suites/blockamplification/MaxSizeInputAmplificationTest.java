// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.payload;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>max-size input bytes</b> amplification vector.
 *
 * <p>The signed transaction itself is a block item, so its wire bytes count against the execution budget directly —
 * before any state-change or output amplification. Input bytes are priced (~$0.00001/byte), but the high-volume lanes
 * throttle by <em>operations</em>, not bytes: max-size transactions across the HIP-1313 high-volume lanes alone are
 * ~7.6× the 26.2 MB/s budget bar in plain input (see {@code .context} reports). The mitigations evaluated are
 * byte-aware high-volume buckets (reusing the jumbo-transaction bytes throttle) and block-utilization pricing.
 *
 * <p>This proof floods near-maximum {@code ConsensusSubmitMessage} transactions (~{@value #MESSAGE_BYTES}-byte message,
 * close to the {@code transaction.maxBytes}=6144 cap), each adding its full signed-transaction size to the block, until
 * the {@value #BUDGET_BYTES}-byte budget is crossed and further submissions are rejected {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p><b>High-volume note:</b> {@code ConsensusSubmitMessage} is not itself a HIP-1313 high-volume functionality; it is
 * used here because it carries a large caller-controlled payload cheaply. The same input-byte pressure applies to any
 * max-size transaction on a high-volume lane (e.g. high-volume {@code FileAppend} or {@code TokenMint}), where the
 * dedicated bucket multiplies the sustainable rate.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class MaxSizeInputAmplificationTest {
    private static final int BUDGET_BYTES = 32_768; // 32 KiB
    private static final int MESSAGE_BYTES = 5_000; // near the 6144-byte transaction cap
    private static final String TOPIC = "amplificationTopic";
    private static final int ATTACK_ATTEMPTS = 14;

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
                            "consensus.message.maxBytesAllowed", "6144",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> maxSizeInputFloodsBlock() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                createTopic(TOPIC).payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "maxSizeInput",
                        ATTACK_ATTEMPTS,
                        () -> submitMessageTo(TOPIC)
                                .message(ByteString.copyFrom(payload(MESSAGE_BYTES)))
                                .payingWith(ATTACKER)
                                .noLogging()));
    }
}
