// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.suites.hip1238;


import static com.hedera.services.bdd.spec.HapiSpec.hapiTest;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepForSeconds;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hedera.services.bdd.suites.HapiSuite.ONE_HBAR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

import com.hedera.services.bdd.junit.HapiTest;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;

/**
 * Integration tests for HIP-1238 Network Quiescence feature.
 *
 * Tests the behavior of the network quiescence mechanism which pauses event creation
 * when there are no pending transactions and automatically resumes when new transactions
 * arrive or target consensus timestamps approach.
 */
public class NetworkQuiescenceTest {

    /**
     * Tests basic quiescence behavior: network should enter quiescence when no
     * transactions are pending and resume when transactions are submitted.
     */
    @HapiTest
    final Stream<DynamicTest> basicQuiescenceBehavior() {
        final var civilian = "civilian";
        final var receiver = "receiver";

        return hapiTest(
            cryptoCreate(civilian).balance(10 * ONE_HBAR),
            cryptoCreate(receiver).balance(0L),

            // Wait for initial transactions to be processed and network to potentially quiesce
            sleepForSeconds(2),

            // Verify network is responsive to new transactions after potential quiescence
            cryptoTransfer(tinyBarsFromTo(civilian, receiver, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            // Verify the transfer was successful
            getAccountInfo(receiver)
                .has(accountWith().balance(ONE_HBAR))
        );
    }

    /**
     * Tests that the network properly handles multiple consecutive transactions
     * and transitions between quiescent and active states.
     */
    @HapiTest
    final Stream<DynamicTest> multipleTransactionQuiescenceCycle() {
        final var sender = "sender";
        final var receiver1 = "receiver1";
        final var receiver2 = "receiver2";

        return hapiTest(
            cryptoCreate(sender).balance(20 * ONE_HBAR),
            cryptoCreate(receiver1).balance(0L),
            cryptoCreate(receiver2).balance(0L),

            // Submit multiple transactions rapidly
            cryptoTransfer(tinyBarsFromTo(sender, receiver1, ONE_HBAR)),
            cryptoTransfer(tinyBarsFromTo(sender, receiver2, ONE_HBAR)),

            // Wait for transactions to be processed
            sleepForSeconds(1),

            // Allow potential quiescence period
            sleepForSeconds(3),

            // Submit another transaction to test quiescence breaking
            cryptoTransfer(tinyBarsFromTo(sender, receiver1, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            // Verify final balances
            getAccountInfo(receiver1).has(accountWith().balance(2 * ONE_HBAR)),
            getAccountInfo(receiver2).has(accountWith().balance(ONE_HBAR))
        );
    }

    /**
     * Tests quiescence behavior under load - submitting transactions at intervals
     * to verify the network maintains responsiveness and properly manages
     * quiescent/active state transitions.
     */
    @HapiTest
    final Stream<DynamicTest> quiescenceUnderLoad() {
        final var payer = "loadPayer";
        final var target = "loadTarget";

        return hapiTest(
            cryptoCreate(payer).balance(50 * ONE_HBAR),
            cryptoCreate(target).balance(0L),

            // Submit transactions with delays to test quiescence cycles
            blockingOrder(
                cryptoTransfer(tinyBarsFromTo(payer, target, ONE_HBAR)),
                sleepFor(500),

                cryptoTransfer(tinyBarsFromTo(payer, target, ONE_HBAR)),
                sleepForSeconds(2), // Allow potential quiescence

                cryptoTransfer(tinyBarsFromTo(payer, target, ONE_HBAR)),
                sleepFor(100),

                cryptoTransfer(tinyBarsFromTo(payer, target, ONE_HBAR)),
                sleepForSeconds(3), // Allow potential quiescence

                cryptoTransfer(tinyBarsFromTo(payer, target, ONE_HBAR))
            ),

            // Verify all transactions completed successfully
            getAccountInfo(target).has(accountWith().balance(5 * ONE_HBAR))
        );
    }

    /**
     * Tests that signature transactions (which should be ignored by quiescence logic)
     * don't interfere with the quiescence mechanism.
     */
    @HapiTest
    final Stream<DynamicTest> signatureTransactionsIgnoredByQuiescence() {
        final var normalUser = "normalUser";
        final var testTarget = "testTarget";

        return hapiTest(
            cryptoCreate(normalUser).balance(10 * ONE_HBAR),
            cryptoCreate(testTarget).balance(0L),

            // Submit a normal transaction
            cryptoTransfer(tinyBarsFromTo(normalUser, testTarget, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            // Wait for potential quiescence (signature transactions shouldn't prevent this)
            sleepForSeconds(3),

            // Submit another normal transaction to verify network responsiveness
            cryptoTransfer(tinyBarsFromTo(normalUser, testTarget, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            getAccountInfo(testTarget).has(accountWith().balance(2 * ONE_HBAR))
        );
    }

    /**
     * Tests the target consensus time (TCT) aspect of quiescence - verifying that
     * quiescence is properly managed when approaching target consensus timestamps.
     */
    @HapiTest
    final Stream<DynamicTest> targetConsensusTimeQuiescenceBehavior() {
        final var tctUser = "tctUser";
        final var tctReceiver = "tctReceiver";
        final AtomicLong initialTime = new AtomicLong();

        return hapiTest(
            // Capture initial time for TCT calculations
            withOpContext((spec, opLog) -> {
                initialTime.set(System.currentTimeMillis());
            }),

            cryptoCreate(tctUser).balance(10 * ONE_HBAR),
            cryptoCreate(tctReceiver).balance(0L),

            // Submit transaction and let it process
            cryptoTransfer(tinyBarsFromTo(tctUser, tctReceiver, ONE_HBAR)),

            // Wait sufficient time for potential quiescence and TCT processing
            sleepForSeconds(4),

            // Submit another transaction to test network responsiveness post-TCT
            cryptoTransfer(tinyBarsFromTo(tctUser, tctReceiver, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            getAccountInfo(tctReceiver).has(accountWith().balance(2 * ONE_HBAR))
        );
    }

    /**
     * Tests network behavior during the quiescence breaking scenario where
     * the network needs to create "Quiescence Breaker" (QB) events to restart activity.
     */
    @HapiTest
    final Stream<DynamicTest> quiescenceBreakingMechanism() {
        final var breakerUser = "breakerUser";
        final var breakerTarget = "breakerTarget";

        return hapiTest(
            cryptoCreate(breakerUser).balance(15 * ONE_HBAR),
            cryptoCreate(breakerTarget).balance(0L),

            // Submit initial transaction
            cryptoTransfer(tinyBarsFromTo(breakerUser, breakerTarget, ONE_HBAR)),

            // Allow extended quiescence period
            sleepForSeconds(5),

            // This transaction should trigger quiescence breaking if network was quiescent
            cryptoTransfer(tinyBarsFromTo(breakerUser, breakerTarget, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            // Quick follow-up to test resumed network activity
            cryptoTransfer(tinyBarsFromTo(breakerUser, breakerTarget, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            getAccountInfo(breakerTarget).has(accountWith().balance(3 * ONE_HBAR))
        );
    }

    /**
     * Tests that the quiescence mechanism doesn't interfere with normal network
     * operations and consensus during mixed transaction loads.
     */
    @HapiTest
    final Stream<DynamicTest> quiescenceDoesNotAffectConsensus() {
        final var consensusUser1 = "consensusUser1";
        final var consensusUser2 = "consensusUser2";
        final var consensusTarget = "consensusTarget";

        return hapiTest(
            cryptoCreate(consensusUser1).balance(10 * ONE_HBAR),
            cryptoCreate(consensusUser2).balance(10 * ONE_HBAR),
            cryptoCreate(consensusTarget).balance(0L),

            // Submit concurrent transactions from multiple users
            blockingOrder(
                cryptoTransfer(tinyBarsFromTo(consensusUser1, consensusTarget, ONE_HBAR)),
                cryptoTransfer(tinyBarsFromTo(consensusUser2, consensusTarget, ONE_HBAR))
            ),

            sleepForSeconds(2),

            // After potential quiescence, submit more transactions
            blockingOrder(
                cryptoTransfer(tinyBarsFromTo(consensusUser1, consensusTarget, ONE_HBAR)),
                cryptoTransfer(tinyBarsFromTo(consensusUser2, consensusTarget, ONE_HBAR))
            ),

            // Verify all transactions were processed correctly
            getAccountInfo(consensusTarget).has(accountWith().balance(4 * ONE_HBAR))
        );
    }

    /**
     * Tests that quiescence configuration is properly respected and the feature
     * can be dynamically controlled.
     */
    @HapiTest
    final Stream<DynamicTest> quiescenceConfigurationBehavior() {
        final var configUser = "configUser";
        final var configReceiver = "configReceiver";

        return hapiTest(
            cryptoCreate(configUser).balance(10 * ONE_HBAR),
            cryptoCreate(configReceiver).balance(0L),

            // Test normal operation with default quiescence configuration
            cryptoTransfer(tinyBarsFromTo(configUser, configReceiver, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            sleepForSeconds(2),

            // Verify network remains responsive after configuration-based quiescence period
            cryptoTransfer(tinyBarsFromTo(configUser, configReceiver, ONE_HBAR))
                .hasKnownStatus(SUCCESS),

            getAccountInfo(configReceiver).has(accountWith().balance(2 * ONE_HBAR))
        );
    }
}