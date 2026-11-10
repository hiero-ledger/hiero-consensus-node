// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.RECEIVER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>ScheduleCreate</b> block-stream amplification vector.
 *
 * <p>A {@code ScheduleCreate} stores the entire inner (scheduled) transaction body in a new {@code Schedule} entity, and
 * that whole entity is emitted as a new-map state change. Because it is a brand-new entity, PR #27718 cannot diff it, so
 * the inner body is effectively written twice (once in the create's own signed transaction, once in the stored
 * schedule) — roughly three times the input in block output (measured ~3,917 bytes for a ~1 KB inner, see
 * {@code .context} reports). Flooding fresh schedules fills the block; once the budget is crossed, further non-exempt
 * transactions are rejected with {@code THROTTLED_AT_CONSENSUS}.
 *
 * <p>{@code ScheduleCreate} is a HIP-1313 high-volume functionality (HVScheduleCreateThrottles), so the second proof
 * marks the creates high-volume: on mainnet that raises the lane from 100 ops/s to 10,000 ops/s for the same per-create
 * price, a 100× increase in how fast the attacker can fill blocks.
 *
 * <p>This vector is also where the two combined PRs meet: once a block is full, {@code #28153} rejects any due schedule
 * whose payer is not throttle-exempt with the same {@code THROTTLED_AT_CONSENSUS} status (and still consumes it), so an
 * attacker flooding schedules degrades not only live submissions but the scheduled-execution backlog as well.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class ScheduleCreateAmplificationTest {
    private static final int ATTACK_ATTEMPTS = 24;

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
                            "blockStream.maxBlockExecutionSizeBytes", "16384",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> scheduleCreateFloodsBlockOnNormalLane() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_MILLION_HBARS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "scheduleCreate-normal",
                        ATTACK_ATTEMPTS,
                        // A distinct, never-signed inner transfer per attempt keeps each schedule unique and pending,
                        // so the create's own output (the stored body) is what fills the block
                        i -> scheduleCreate(
                                        "amplSchedule" + i, cryptoTransfer(tinyBarsFromTo(RECEIVER, FUNDING, 1L + i)))
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
                            "blockStream.maxBlockExecutionSizeBytes", "16384",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> scheduleCreateFloodsBlockOnHighVolumeLane() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(ONE_MILLION_HBARS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "scheduleCreate-highVolume",
                        ATTACK_ATTEMPTS,
                        i -> scheduleCreate(
                                        "amplScheduleHv" + i, cryptoTransfer(tinyBarsFromTo(RECEIVER, FUNDING, 1L + i)))
                                .payingWith(ATTACKER)
                                .withHighVolume()
                                .noLogging()));
    }
}
