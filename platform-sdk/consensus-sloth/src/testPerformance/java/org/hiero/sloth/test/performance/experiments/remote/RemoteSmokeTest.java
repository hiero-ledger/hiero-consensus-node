// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.test.performance.experiments.remote;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.sloth.fixtures.Benchmark;
import org.hiero.sloth.fixtures.Network;
import org.hiero.sloth.fixtures.TestEnvironment;
import org.hiero.sloth.fixtures.TimeManager;
import org.hiero.sloth.fixtures.specs.RemoteSpecs;
import org.hiero.sloth.fixtures.specs.SlothSpecs;
import org.junit.jupiter.api.Disabled;

/**
 * A simple smoke test that verifies the remote SSH execution environment works correctly. Starts two nodes on two
 * remote hosts, waits for them to become active, then shuts down.
 */
@Disabled
@SlothSpecs(randomNodeIds = false)
@RemoteSpecs(hosts = "perf1,perf2", remoteJavaPath = "/opt/sloth/jdk/bin/java")
public class RemoteSmokeTest {

    private static final Logger log = LogManager.getLogger(RemoteSmokeTest.class);

    @Benchmark
    void smokeTest(@NonNull final TestEnvironment env) {
        log.info("=== Remote Smoke Test ===");

        final Network network = env.network();
        final TimeManager timeManager = env.timeManager();

        network.addNodes(2);

        log.info("Starting network on remote hosts...");
        network.start();
        log.info("All nodes are ACTIVE");

        // Let the network run briefly to verify stability
        timeManager.waitFor(Duration.ofSeconds(10));
        log.info("Network stable for 10 seconds");

        network.shutdown();
        log.info("=== Remote Smoke Test completed successfully ===");
    }
}
