// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.test;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.hiero.otter.fixtures.OtterAssertions.assertContinuouslyThat;
import static org.hiero.otter.fixtures.OtterAssertions.assertThat;
import static org.hiero.otter.fixtures.assertions.StatusProgressionStep.target;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.network.transactions.CreateAccountTransaction;
import org.hiero.otter.fixtures.network.transactions.DeleteAccountTransaction;
import org.hiero.otter.fixtures.network.transactions.OtterTransaction;
import org.hiero.otter.fixtures.result.MultipleNodeConsensusResults;
import org.hiero.otter.fixtures.result.MultipleNodePlatformStatusResults;
import org.hiero.otter.fixtures.result.SingleNodeConsensusResult;

class LargeStateTests {

    private static final int LARGE_COUNT = 1_000_000;

    // A basic test to make sure the service works in general
    @OtterTest
    void basicAccountsTest(@NonNull final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.withConfigValue("event.services", "org.hiero.otter.fixtures.app.services.accounts.AccountsService");
        network.addNodes(4);
        network.start();

        timeManager.waitFor(Duration.ofSeconds(4));

        // Create a few accounts
        createAccounts(10, network, timeManager);

        timeManager.waitFor(Duration.ofSeconds(4));

        // Delete an account with ID=2, it must exist
        deleteAccount(2, network);

        // Account ID=100 should not exist
        deleteAccount(100, network);

        timeManager.waitFor(Duration.ofSeconds(4));

        for (final Node node : network.nodes()) {
            final List<StructuredLog> logs = node.newLogResult().logs();
            assertTrue(checkAccountDeleted(2, logs), "Failed to wait till account 2 is deleted");
            assertTrue(checkAccountFailedToDelete(100, logs), "Failed to wait till account 100 failed to delete");
        }

        network.shutdown();
    }

    @OtterTest
    void largeRestartTest(@NonNull final TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.withConfigValue("event.services", "org.hiero.otter.fixtures.app.services.accounts.AccountsService");
        network.addNodes(4);
        assertContinuouslyThat(network.newLogResults()).haveNoErrorLevelMessages();
        network.start();

        // Create some accounts
        timeManager.waitFor(Duration.ofSeconds(4L));
        createAccounts(LARGE_COUNT, network, timeManager);

        // Make sure all accounts are created
        timeManager.waitFor(Duration.ofMinutes(5L));
//        for (final Node node : network.nodes()) {
//            timeManager.waitForCondition(
//                    () -> {
//                        final List<StructuredLog> logs = node.newLogResult().logs();
//                        return logsMatchesCount(logs, "Account created: id=(\\d)+ name=Test(\\d)+") == LARGE_COUNT;
//                    },
//                    Duration.ofMinutes(5L),
//                    "Failed waiting for accounts to create");
//        }

        // Restart all the nodes
        network.shutdown();

        // Verify that the node was healthy prior to being killed
        final MultipleNodePlatformStatusResults networkStatusResults = network.newPlatformStatusResults();
        assertThat(networkStatusResults)
                .haveSteps(target(ACTIVE).requiringInterim(REPLAYING_EVENTS, OBSERVING, CHECKING));
        networkStatusResults.clear();

        final MultipleNodeConsensusResults networkConsensusResults = network.newConsensusResults();
        networkConsensusResults.results().forEach(networkConsensusResult -> {
            System.out.println("Node " + networkConsensusResult.nodeId() + " reached round " + networkConsensusResult.lastRoundNum());
        });
        final long lastRoundReached = networkConsensusResults.results().stream()
                .map(SingleNodeConsensusResult::lastRoundNum)
                .max(Long::compareTo)
                .orElseThrow(() -> new IllegalStateException("No consensus rounds found"));
        networkConsensusResults.clear();

        network.withTimeout(Duration.ofMinutes(5L)).start();

//        // Wait for all nodes to advance at least 20 rounds beyond the last round reached
//        timeManager.waitForCondition(
//                () -> network.newConsensusResults().allNodesAdvancedToRound(lastRoundReached + 20),
//                Duration.ofSeconds(60L));
//
//        // Delete all accounts to make sure they were loaded from the snapshot after restart
//        deleteAccounts(LARGE_COUNT, network, timeManager);
//        deleteAccount(LARGE_COUNT + 1, network);
//
//        // Make sure accounts are found and deleted
//        timeManager.waitFor(Duration.ofSeconds(4L));
//        for (final Node node : network.nodes()) {
//            timeManager.waitForCondition(
//                    () -> {
//                        final List<StructuredLog> logs = node.newLogResult().logs();
//                        return (logsMatchesCount(logs, "Account deleted: id=(\\d)+") == LARGE_COUNT)
//                                && checkAccountFailedToDelete(LARGE_COUNT + 1, logs);
//                    },
//                    Duration.ofSeconds(60L),
//                    "Failed waiting for accounts to delete");
//        }
//
//        network.shutdown();
    }

    // Helper test methods

    private static void createAccounts(
            final int count, @NonNull final Network network, @NonNull final TimeManager time)
            throws InterruptedException {
        final int BATCH = 100;
        final List<OtterTransaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final CreateAccountTransaction createAccountTransaction =
                    CreateAccountTransaction.newBuilder().setName("Test" + i).build();
            final OtterTransaction createOtterTransaction = OtterTransaction.newBuilder()
                    .setCreateAccountTransaction(createAccountTransaction)
                    .build();
            transactions.add(createOtterTransaction);
            if ((i > 0) && (i % BATCH == 0)) {
                network.submitTransactions(transactions);
                transactions.clear();
                Thread.sleep(10);
            }
        }
        if (!transactions.isEmpty()) {
            network.submitTransactions(transactions);
            transactions.clear();
        }
    }

    private static void deleteAccounts(
            final int count, @NonNull final Network network, @NonNull final TimeManager time)
            throws InterruptedException {
        final int BATCH = 100;
        final List<OtterTransaction> transactions = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            final DeleteAccountTransaction deleteAccountTransaction =
                    DeleteAccountTransaction.newBuilder().setId(i).build();
            final OtterTransaction deleteOtterTransaction = OtterTransaction.newBuilder()
                    .setDeleteAccountTransaction(deleteAccountTransaction)
                    .build();
            transactions.add(deleteOtterTransaction);
            if (i % BATCH == 0) {
                network.submitTransactions(transactions);
                transactions.clear();
                Thread.sleep(10);
            }
        }
        if (!transactions.isEmpty()) {
            network.submitTransactions(transactions);
            transactions.clear();
        }
    }

    private static void deleteAccount(final int id, @NonNull final Network network) {
        final DeleteAccountTransaction deleteAccountTransaction =
                DeleteAccountTransaction.newBuilder().setId(id).build();
        final OtterTransaction deleteOtterTransaction = OtterTransaction.newBuilder()
                .setDeleteAccountTransaction(deleteAccountTransaction)
                .build();
        network.submitTransaction(deleteOtterTransaction);
    }

    private static boolean logsContain(@NonNull final List<StructuredLog> logs, @NonNull final String pattern) {
        for (final StructuredLog log : logs) {
            if (log.message().matches(".*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }

    private static int logsMatchesCount(@NonNull final List<StructuredLog> logs, @NonNull final String pattern) {
        int result = 0;
        for (final StructuredLog log : logs) {
            if (log.message().matches(".*" + pattern + ".*")) {
                result++;
            }
        }
        return result;
    }

    private static boolean checkAccountDeleted(final int id, @NonNull final List<StructuredLog> logs) {
        return logsContain(logs, "Account deleted: id=" + id);
    }

    private static boolean checkAccountFailedToDelete(final int id, @NonNull final List<StructuredLog> logs) {
        return logsContain(logs, "Account not deleted, doesn't exist: id=" + id);
    }
}
