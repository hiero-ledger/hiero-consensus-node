package org.hiero.otter.test;

import static org.hiero.otter.fixtures.TransactionGenerator.INFINITE;

import java.time.Duration;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.OtterTest;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator.Distribution;
import org.hiero.otter.fixtures.TransactionGenerator.Rate;

class BirthRoundMigrationTest {

    @OtterTest
    void testBirthRoundMigration(TestEnvironment env) throws InterruptedException {
        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        // Setup simulation
        network.addNodes(4);
        network.start(Duration.ofMinutes(1L));
        env.generator().generateTransactions(INFINITE, Rate.fixedRateWithTps(1000), Distribution.UNIFORM);

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Initiate the migration
        env.generator().pause();
        network.prepareUpgrade(Duration.ofMinutes(1L));

        // update the configuration
        for (final Node node : network.getNodes()) {
            node.getConfiguration().set(EventConfig_.USE_BIRTH_ROUND_ANCIENT_THRESHOLD, true);
        }

        // restart the network
        network.resume(Duration.ofMinutes(1L));
        env.generator().resume();

        // Wait for 30 seconds
        timeManager.waitFor(Duration.ofSeconds(30L));

        // Validations
        env.validator()
                .assertPlatformStatus()
                .assertLogErrors()
                .assertMetrics();
    }
}
