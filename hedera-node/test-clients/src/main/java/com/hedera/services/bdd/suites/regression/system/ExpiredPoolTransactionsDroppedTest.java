// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.regression.system;

import static com.hedera.services.bdd.junit.TestTags.RESTART;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.byNodeId;
import static com.hedera.services.bdd.junit.hedera.NodeSelector.exceptNodeIds;
import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertHgcaaLogContainsTimeframe;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.doAdhoc;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freezeOnly;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.overridingTwo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.waitForActiveNetworkWithReassignedPorts;
import static com.hedera.services.bdd.suites.HapiSuite.FUNDING;
import static com.hedera.services.bdd.suites.HapiSuite.GENESIS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.RECEIPT_NOT_FOUND;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.services.bdd.junit.HapiTestLifecycle;
import com.hedera.services.bdd.junit.LeakyHapiTest;
import com.hedera.services.bdd.junit.OrderedInIsolation;
import com.hedera.services.bdd.junit.hedera.subprocess.SubProcessNode.ReassignPorts;
import com.hedera.services.bdd.spec.SpecOperation;
import com.hedera.services.bdd.spec.utilops.lifecycle.ops.TryToStartNodesOp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;

/**
 * Verifies that a node drops a user transaction that expires while waiting in its transaction pool, instead of putting
 * it in an event, where it could only fail at consensus as the node's due-diligence failure.
 */
@Tag(RESTART)
@HapiTestLifecycle
@OrderedInIsolation
class ExpiredPoolTransactionsDroppedTest implements LifecycleTest {
    private static final long SLOW_NODE_ID = 1L;
    private static final String SLOW_NODE_ACCOUNT = "4";
    private static final String OTHER_NODE_ACCOUNT = "3";
    // Node 1 creates an event at most every 50s, so user transactions wait in its pool, and drops them right at expiry
    private static final Map<String, String> SLOW_NODE = Map.of(
            "event.creation.maxCreationRate",
            "0.02",
            "platformStatus.activeStatusDelay",
            "300s",
            "hedera.transaction.expiredPoolDropMarginSecs",
            "0");
    // The harness backdates valid start by 60s (txn.start.offset.secs), rounded down to the second, and ingest here
    // wants 2s of validity left: a transfer has at least 12s to reach node 1, and expires within 15s of being built
    private static final long SHORT_VALID_SECS = 75;
    private static final long MIN_VALIDITY_BUFFER_SECS = 2;
    private static final long LONG_VALID_SECS = 600;
    private static final int EXPIRING_COUNT = 5;

    @LeakyHapiTest(overrides = {"hedera.transaction.maxValidDuration", "hedera.transaction.minValidityBufferSecs"})
    final Stream<DynamicTest> dropsTransactionsThatExpireInThePool() {
        final AtomicReference<Instant> testStart = new AtomicReference<>();
        final var expiringTransfers = IntStream.range(0, EXPIRING_COUNT)
                .mapToObj(i -> cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))
                        .setNode(SLOW_NODE_ACCOUNT)
                        .validDurationSecs(SHORT_VALID_SECS)
                        .via("expiring" + i)
                        .fireAndForget())
                .toList();
        return hapiTest(
                overridingTwo(
                        "hedera.transaction.maxValidDuration", String.valueOf(LONG_VALID_SECS),
                        "hedera.transaction.minValidityBufferSecs", String.valueOf(MIN_VALIDITY_BUFFER_SECS)),
                restartWithSlowNodeOverrides(SLOW_NODE),
                doAdhoc(() -> testStart.set(Instant.now())),
                // Once this reaches consensus, node 1 has just created an event, so its next one is about 50s away
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)).setNode(SLOW_NODE_ACCOUNT),
                // These expire about 35s before that next event; they never get a status, so none is awaited
                blockingOrder(expiringTransfers.toArray(SpecOperation[]::new)),
                // fireAndForget() accepts any precheck, and a transfer node 1 rejected would never be in its pool
                doAdhoc(() -> expiringTransfers.forEach(transfer -> assertEquals(
                        OK, transfer.getActualPrecheck(), "Node 1 rejected a short-lived transfer at precheck"))),
                // Submitted after them and still valid at that event, so once this reaches consensus, so has the event
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1))
                        .setNode(SLOW_NODE_ACCOUNT)
                        .validDurationSecs(LONG_VALID_SECS),
                // No node ever saw the expired transfers in an event
                blockingOrder(IntStream.range(0, EXPIRING_COUNT)
                        .mapToObj(i -> getReceipt("expiring" + i)
                                .setNode(OTHER_NODE_ACCOUNT)
                                .hasAnswerOnlyPrecheck(RECEIPT_NOT_FOUND))
                        .toArray(SpecOperation[]::new)),
                // Node 1 dropped exactly these, so it had also accepted all of them
                assertHgcaaLogContainsTimeframe(
                        byNodeId(SLOW_NODE_ID),
                        testStart::get,
                        Duration.ofMinutes(5),
                        Duration.ofSeconds(30),
                        "Dropped " + EXPIRING_COUNT + " expired user transactions from the transaction pool"),
                // Back to normal event creation for the tests that follow
                restartWithSlowNodeOverrides(Map.of()));
    }

    private static SpecOperation restartWithSlowNodeOverrides(final Map<String, String> slowNodeOverrides) {
        return blockingOrder(
                freezeOnly().startingIn(5).seconds().payingWith(GENESIS).deferStatusResolution(),
                cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)),
                LifecycleTest.confirmFreezeAndShutdown(),
                sourcing(() -> {
                    final int version = LifecycleTest.CURRENT_CONFIG_VERSION.incrementAndGet();
                    return blockingOrder(
                            new TryToStartNodesOp(exceptNodeIds(SLOW_NODE_ID), version, ReassignPorts.YES, Map.of()),
                            new TryToStartNodesOp(byNodeId(SLOW_NODE_ID), version, ReassignPorts.NO, slowNodeOverrides),
                            waitForActiveNetworkWithReassignedPorts(LifecycleTest.RESTART_TIMEOUT));
                }));
    }
}
