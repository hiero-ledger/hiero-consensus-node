// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.WRAPS;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.untilHgcaaLogContainsText;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_BILLION_HBARS;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_MILLION_HBARS;

import com.hedera.services.bdd.junit.HapiTest;
import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.support.TestLifecycle;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.dsl.annotations.Account;
import com.hedera.services.bdd.spec.dsl.entities.SpecAccount;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 */
@Tag(WRAPS)
@HapiTestLifecycle
@OrderedInIsolation
public class WrapsHandoffsTest implements LifecycleTest {
    private static final String GENESIS_WRAPS_PROOF_CONSTRUCTED = "FINISHED constructing genesis WRAPS proof";
    private static final String INCREMENTAL_WRAPS_PROOF_STARTED = "Constructing incremental WRAPS proof";
    private static final String INCREMENTAL_WRAPS_PROOF_CONSTRUCTED = "FINISHED constructing incremental WRAPS proof";
    private static final Duration WRAPS_PROOF_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration STAKE_PERIOD_DURATION = Duration.ofMinutes(16);
    private static final Duration LOG_POLL_INTERVAL = Duration.ofSeconds(1);
    private static final long TRANSFER_PACING_MS = 250L;
    private static final Random RANDOM = new Random(2_721_828L);

    @Account(tinybarBalance = ONE_BILLION_HBARS, stakedNodeId = 0)
    static SpecAccount NODE0_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 1)
    static SpecAccount NODE1_STAKER;

    @Account(tinybarBalance = ONE_BILLION_HBARS / 100, stakedNodeId = 2)
    static SpecAccount NODE2_STAKER;

    @Account(tinybarBalance = ONE_MILLION_HBARS / 100, stakedNodeId = 3)
    static SpecAccount NODE3_STAKER;

    @BeforeAll
    public static void setup(TestLifecycle lifecycle) {
        lifecycle.doAdhoc(
                NODE0_STAKER.getInfo(), NODE1_STAKER.getInfo(), NODE2_STAKER.getInfo(), NODE3_STAKER.getInfo());
    }

    @HapiTest
    final Stream<DynamicTest> genesisAndIncrementalWrapsProofsConstructed() {
        return hapiTest(
                untilHgcaaLogContainsText(
                                byNodeId(0),
                                GENESIS_WRAPS_PROOF_CONSTRUCTED,
                                WRAPS_PROOF_TIMEOUT,
                                LOG_POLL_INTERVAL,
                                () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)})
                        .loggingOff(),
                untilHgcaaLogContainsText(
                                byNodeId(0),
                                INCREMENTAL_WRAPS_PROOF_STARTED,
                                STAKE_PERIOD_DURATION,
                                LOG_POLL_INTERVAL,
                                () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)})
                        .loggingOff(),
                untilHgcaaLogContainsText(
                                byNodeId(0),
                                INCREMENTAL_WRAPS_PROOF_CONSTRUCTED,
                                WRAPS_PROOF_TIMEOUT.plus(WRAPS_PROOF_TIMEOUT),
                                LOG_POLL_INTERVAL,
                                () -> new SpecOperation[] {randomStakerTransfer(), sleepFor(TRANSFER_PACING_MS)})
                        .loggingOff());
    }

    private static SpecOperation randomStakerTransfer() {
        final var stakers = stakers();
        final var senderIndex = RANDOM.nextInt(stakers.size());
        var receiverIndex = RANDOM.nextInt(stakers.size() - 1);
        if (receiverIndex >= senderIndex) {
            receiverIndex++;
        }
        final var sender = stakers.get(senderIndex);
        final var receiver = stakers.get(receiverIndex);
        final long amount = RANDOM.nextLong(10L, 101L) * ONE_HBAR;
        return cryptoTransfer(tinyBarsFromTo(sender.name(), receiver.name(), amount))
                .payingWith(sender.name());
    }

    private static List<SpecAccount> stakers() {
        return List.of(NODE0_STAKER, NODE1_STAKER, NODE2_STAKER, NODE3_STAKER);
    }
}
