package org.hiero.otter.test;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.List;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.app.CreateAccountTransaction;
import org.hiero.otter.fixtures.app.DeleteAccountTransaction;
import org.hiero.otter.fixtures.app.OtterTransaction;
import org.hiero.otter.fixtures.logging.StructuredLog;

class AccountServiceTest {

    @OtterTest
    void createSingleAccount(@NonNull final TestEnvironment env) {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.withConfigValue("event.services", "org.hiero.otter.fixtures.app.services.accounts.AccountsService");
        network.addNodes(4);
        network.start();

        timeManager.waitFor(Duration.ofSeconds(4));

        // Create a few accounts
        createAccounts(10, network);

        timeManager.waitFor(Duration.ofSeconds(4));

        // Delete an account with ID=2, it must exist
        final DeleteAccountTransaction deleteAccount2Transaction =
                DeleteAccountTransaction.newBuilder().setId(2).build();
        final OtterTransaction delete2OtterTransaction =
                OtterTransaction.newBuilder().setDeleteAccountTransaction(deleteAccount2Transaction).build();
        network.submitTransaction(delete2OtterTransaction);

        // Account ID=100 should not exist
        final DeleteAccountTransaction deleteAccount100Transaction =
                DeleteAccountTransaction.newBuilder().setId(100).build();
        final OtterTransaction delete100OtterTransaction =
                OtterTransaction.newBuilder().setDeleteAccountTransaction(deleteAccount100Transaction).build();
        network.submitTransaction(delete100OtterTransaction);

        timeManager.waitFor(Duration.ofSeconds(4));

        for (final Node node : network.nodes()) {
            final List<StructuredLog> logs = node.newLogResult().logs();
            assertTrue(logsContain(logs, "Account deleted: id=2"), "Failed to delete account 2");
            assertTrue(logsContain(logs, "Account not deleted, doesn't exist: id=100"));
        }

        network.shutdown();
    }

    private static void createAccounts(final int count, final Network network) {
        for (int i = 0; i < count; i++) {
            final CreateAccountTransaction createAccountTransaction =
                    CreateAccountTransaction.newBuilder().setName("Test" + i).build();
            final OtterTransaction createOtterTransaction =
                    OtterTransaction.newBuilder().setCreateAccountTransaction(createAccountTransaction).build();
            network.submitTransaction(createOtterTransaction);
        }

        for (final Node node : network.nodes()) {
            assertEventuallyTrue(() -> {
                final List<StructuredLog> logs = node.newLogResult().logs();
                for (int i = 0; i < count; i++) {
                    if (!logsContain(logs, "Account created: id=(\\d)+ name=Test" + i)) {
                        return false;
                    }
                }
                return true;
            }, Duration.ofSeconds(10), "Failed waiting for accounts to create");
        }
    }

    private static boolean logsContain(final List<StructuredLog> logs, final String pattern) {
        for (final StructuredLog log : logs) {
            if (log.message().matches(".*" + pattern + ".*")) {
                return true;
            }
        }
        return false;
    }
}
