// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.blockamplification;

import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingThrottles;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HUNDRED_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.ATTACKER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.MAINNET_THROTTLES_WITH_HV;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.RECEIVER;
import static com.hedera.services.bdd.suites.blockamplification.BlockAmplificationSupport.floodAssertingBudgetRejection;

import com.hedera.services.bdd.GenesisSubprocessTest;
import com.hedera.services.bdd.GenesisSubprocessTest.SubProcessNodeConfig;
import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.spec.HapiSpec;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hederahashgraph.api.proto.java.TransferList;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Proof of concept for the <b>ScheduleSign</b> signatories amplification vector (catalog entry: ScheduleSign re-emits
 * signatories).
 *
 * <p>A schedule stores the set of keys that have signed it, and every {@code ScheduleSign} re-emits the whole schedule
 * entity — including the growing signatories list — as a state change. A schedule that needs many signers therefore
 * re-emits an O(n) list on each of its n signings, O(n²) total, each signing priced as one cheap {@code ScheduleSign}.
 *
 * <p>This proof creates a schedule whose inner transfer debits {@value #SIGNERS} distinct signer accounts (so all of
 * them must sign), then floods {@code ScheduleSign}s that each add one signer, re-emitting the schedule each time until
 * the small {@value #BUDGET_BYTES}-byte budget is crossed and further signings are rejected
 * {@code THROTTLED_AT_CONSENSUS}. This is a low-severity vector (the per-signing re-emit is modest); the optional
 * mitigation is a {@code maxSignatories} bound.
 *
 * <p><b>High-volume note:</b> {@code ScheduleSign} is not a HIP-1313 high-volume functionality (its mainnet lane is
 * 100 ops/s), so there is no high-volume variant.
 */
@OrderedInIsolation
@SuppressWarnings("java:S2699") // assertions live in floodAssertingBudgetRejection
public class ScheduleSignAmplificationTest {
    private static final int BUDGET_BYTES = 4_096; // 4 KiB
    private static final int SIGNERS = 9; // 9 debits + 1 credit = 10, the transfers.maxLen cap
    private static final String SIGNER_PREFIX = "scheduleSigner";
    private static final String SCHEDULE = "amplificationSignSchedule";

    private static SpecOperation createSigners() {
        return withOpContext((spec, opLog) -> {
            for (int i = 0; i < SIGNERS; i++) {
                allRunFor(
                        spec,
                        cryptoCreate(SIGNER_PREFIX + i)
                                .balance(ONE_HUNDRED_HBARS)
                                .payingWith(GENESIS));
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Function<HapiSpec, TransferList>[] manySourceDebits() {
        final Function<HapiSpec, TransferList>[] debits = new Function[SIGNERS];
        for (int i = 0; i < SIGNERS; i++) {
            debits[i] = tinyBarsFromTo(SIGNER_PREFIX + i, RECEIVER, 1L);
        }
        return debits;
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
                            "blockStream.maxBlockExecutionSizeBytes", "4096",
                            "blockStream.maxBlockSizeLimitEnabled", "true",
                            "blockStream.blockPeriod", "10s",
                            "scheduling.longTermEnabled", "true",
                            "networkAdmin.highVolumeThrottlesEnabled", "true"
                        })
            })
    final Stream<DynamicTest> scheduleSignReEmitsSignatoriesUntilBudgetCrossed() {
        return hapiTest(
                overridingThrottles(MAINNET_THROTTLES_WITH_HV),
                cryptoCreate(ATTACKER).balance(ONE_MILLION_HBARS),
                cryptoCreate(RECEIVER).balance(0L),
                createSigners(),
                scheduleCreate(SCHEDULE, cryptoTransfer(manySourceDebits())).payingWith(GENESIS),
                sleepForSeconds(11),
                floodAssertingBudgetRejection(
                        "scheduleSign",
                        SIGNERS,
                        // Each signing adds one signer, re-emitting the schedule with its growing signatories list
                        i -> scheduleSign(SCHEDULE)
                                .alsoSigningWith(SIGNER_PREFIX + i)
                                .payingWith(ATTACKER)
                                .noLogging()));
    }
}
