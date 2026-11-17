// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.freeze;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.exceptNodeIds;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overriding;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runBackgroundTrafficUntilFreezeComplete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActiveNetworkWithReassignedPorts;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_EXPIRED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode.ReassignPorts;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.TryToStartNodesOp;
import com.hedera.services.bdd.suites.regression.system.LifecycleTest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that user transactions still in a node's transaction pool at a freeze are saved and resubmitted after the
 * restart (#26913), and that saved transactions that expired meanwhile are dropped.
 */
@Tag(RESTART)
@HapiTestLifecycle
@OrderedInIsolation
class FreezePendingTransactionsRestartTest implements LifecycleTest {
    private static final long SLOW_NODE_ID = 1L;
    private static final String SLOW_NODE_ACCOUNT = "4";
    private static final String PERSISTENCE_FLAG = "hedera.transaction.pendingTransactionsPersistenceEnabled";
    private static final Map<String, String> FLAG_ON = Map.of(PERSISTENCE_FLAG, "true");
    // Small residual flake: node 1 can still create an event between the last submission and FREEZING, leaving
    // nothing stranded. The assertion below names this flake explicitly when it happens.
    private static final Map<String, String> SLOW_NODE = Map.of(
            PERSISTENCE_FLAG,
            "true",
            "event.creation.maxCreationRate",
            "0.02",
            "platformStatus.activeStatusDelay",
            "300s");
    private static final long LONG_VALID_SECS = 600;
    // 80s: after 60s harness backdating + 10s minValidityBuffer, ~10s left at submission; expired by restore
    private static final long SHORT_VALID_SECS = 80;

    @LeakyHapiTest(overrides = {"hedera.transaction.maxValidDuration"})
    final Stream<DynamicTest> savesPoolAtFreezeAndRestoresAfterRestart() {
        final AtomicReference<Instant> testFreezeStart = new AtomicReference<>();
        final AtomicReference<Instant> submitDeadline = new AtomicReference<>();
        final List<String> longValidAccepted = new ArrayList<>();
        return hapiTest(
                overriding("hedera.transaction.maxValidDuration", String.valueOf(LONG_VALID_SECS)),
                // Setup restart: node 1 creates events rarely, so user transactions wait in its pool
                freezeOnly().startingIn(5).seconds().payingWith(GENESIS).deferStatusResolution(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                LifecycleTest.confirmFreezeAndShutdown(),
                startNodes(FLAG_ON, SLOW_NODE),
                // Freeze under test, with transfers piling up in node 1's pool
                doAdhoc(() -> testFreezeStart.set(Instant.now())),
                runBackgroundTrafficUntilFreezeComplete(),
                // Stop submitting well before the freeze round so no submission is ever in flight when node 1
                // leaves ACTIVE; FREEZING is short-lived and gRPC shuts down at FREEZE_COMPLETE
                doAdhoc(() -> submitDeadline.set(Instant.now().plusSeconds(10).minusMillis(500))),
                freezeOnly().startingIn(10).seconds().payingWith(GENESIS).deferStatusResolution(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                submitToSlowNodeUntilFrozen(submitDeadline, longValidAccepted),
                LifecycleTest.confirmFreezeAndShutdown(),
                // Node 1 must have saved something at the freeze, otherwise this run tests nothing
                withOpContext((spec, opLog) -> {
                    try {
                        allRunFor(
                                spec,
                                assertHgcaaLogContainsTimeframe(
                                        byNodeId(SLOW_NODE_ID),
                                        testFreezeStart::get,
                                        Duration.ofMinutes(15),
                                        Duration.ofSeconds(90),
                                        "pending user transactions at freeze round"));
                    } catch (final Throwable e) {
                        throw new AssertionError(
                                "Node 1 saved no pending user transactions at the freeze: nothing was stranded. "
                                        + "Known timing flake: node 1 created an event between the last "
                                        + "submission and FREEZING",
                                e);
                    }
                }),
                // Restart all nodes at normal speed; node 1 resubmits what it saved. The flag stays on for the
                // shared network until the next restart.
                startNodes(FLAG_ON, FLAG_ON),
                // Sync point: wait for the restore to finish before checking receipts
                assertHgcaaLogContainsTimeframe(
                        byNodeId(SLOW_NODE_ID),
                        testFreezeStart::get,
                        Duration.ofMinutes(15),
                        Duration.ofSeconds(90),
                        "pending user transactions (dropped:",
                        "TRANSACTION_EXPIRED="),
                // Node 1 puts its pool into events in order, so once this transfer reaches consensus, every
                // restored transfer has too
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).setNode(SLOW_NODE_ACCOUNT),
                sourcing(() -> blockingOrder(longValidAccepted.stream()
                        .map(name -> getReceipt(name).hasPriorityStatusFrom(SUCCESS, BUSY))
                        .toArray(SpecOperation[]::new))));
    }

    private static SpecOperation startNodes(final Map<String, String> otherNodes, final Map<String, String> slowNode) {
        return sourcing(() -> {
            final int version = LifecycleTest.CURRENT_CONFIG_VERSION.incrementAndGet();
            return blockingOrder(
                    new TryToStartNodesOp(exceptNodeIds(SLOW_NODE_ID), version, ReassignPorts.YES, otherNodes),
                    new TryToStartNodesOp(byNodeId(SLOW_NODE_ID), version, ReassignPorts.NO, slowNode),
                    waitForActiveNetworkWithReassignedPorts(LifecycleTest.RESTART_TIMEOUT));
        });
    }

    private static SpecOperation submitToSlowNodeUntilFrozen(
            final AtomicReference<Instant> submitDeadline, final List<String> longValidAccepted) {
        return withOpContext((spec, opLog) -> {
            int accepted = 0;
            int shortValidAccepted = 0;
            final var notReadyDeadline = Instant.now().plusSeconds(30);
            // Loop termination is clock-driven (submitDeadline, set well before the freeze round), not
            // rejection-driven: an op must never be in flight against node 1 when it leaves ACTIVE
            for (int i = 0; Instant.now().isBefore(submitDeadline.get()); i++) {
                // Every 4th transfer expires long before the restart finishes
                final boolean shortValid = i % 4 == 0;
                final var name = "stranded" + i;
                // Receipts are checked after the restart; a deferred status check here would poll across it
                final var transfer = cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))
                        .setNode(SLOW_NODE_ACCOUNT)
                        .validDurationSecs(shortValid ? SHORT_VALID_SECS : LONG_VALID_SECS)
                        .via(name)
                        .fireAndForget()
                        .noLogging();
                allRunFor(spec, transfer);
                final var precheck = transfer.getActualPrecheck();
                if (precheck == TRANSACTION_EXPIRED) {
                    // A short-valid transfer can expire before it is even submitted (see txn.start.offset.secs in
                    // spec-default.properties) or while HapiTxnOp retries a transient precheck internally; neither
                    // is a sign that node 1 has frozen, so this never counts against the freeze signal below
                    opLog.info(
                            "Node {} short-valid transfer expired before landing (precheck {}), retrying",
                            SLOW_NODE_ID,
                            precheck);
                    MILLISECONDS.sleep(50);
                    continue;
                }
                if (precheck != OK) {
                    // Right after restart node 1 can briefly reject before it is fully ready; only treat a
                    // rejection as the freeze once we have seen it accept at least one transfer
                    if (accepted == 0 && Instant.now().isBefore(notReadyDeadline)) {
                        opLog.info(
                                "Node {} not yet accepting transfers (precheck {}), retrying", SLOW_NODE_ID, precheck);
                        MILLISECONDS.sleep(200);
                        continue;
                    }
                    break;
                }
                accepted++;
                if (shortValid) {
                    shortValidAccepted++;
                } else {
                    longValidAccepted.add(name);
                }
                MILLISECONDS.sleep(50);
            }
            opLog.info(
                    "Node {} accepted {} transfers before freezing ({} short-valid)",
                    SLOW_NODE_ID,
                    accepted,
                    shortValidAccepted);
            assertTrue(accepted > 0, "Node " + SLOW_NODE_ID + " accepted no transfers before freezing");
            assertTrue(
                    !longValidAccepted.isEmpty(),
                    "Node " + SLOW_NODE_ID + " accepted no long-valid transfers before freezing");
        });
    }
}
