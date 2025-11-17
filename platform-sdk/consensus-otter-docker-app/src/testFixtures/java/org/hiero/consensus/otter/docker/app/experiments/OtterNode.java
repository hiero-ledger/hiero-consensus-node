// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.experiments;

import static org.hiero.otter.fixtures.internal.helpers.Utils.createConfiguration;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.io.config.FileSystemManagerConfig_;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.metrics.config.MetricsConfig_;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.config.PathsConfig_;
import com.swirlds.platform.listeners.PlatformStatusChangeListener;
import com.swirlds.platform.listeners.PlatformStatusChangeNotification;
import com.swirlds.platform.util.TimestampCollector;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hiero.consensus.config.EventConfig_;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.otter.docker.app.platform.ConsensusNodeManager;

public class OtterNode {

    public void start(
            @NonNull final NodeId selfId, @NonNull final KeysAndCerts keysAndCerts, @NonNull final Roster roster) {
        try {
            System.out.println("Starting OtterNode");

            TimestampCollector.INSTANCE.init(selfId);

            final Path outputDirectory = Path.of("build", "remote", "node-" + selfId.id());

            // Create configuration with default properties
            final Map<String, String> overriddenProperties = getOverriddenProperties(outputDirectory);
            final Configuration configuration = createConfiguration(overriddenProperties);

            // Define semantic version
            final SemanticVersion version =
                    SemanticVersion.newBuilder().major(1).build();

            // Create background executor
            final ExecutorService backgroundExecutor = Executors.newCachedThreadPool();

            // Delete old data folder
            try {
                if (Files.exists(outputDirectory)) {
                    FileUtils.deleteDirectory(outputDirectory);
                }
            } catch (final IOException e) {
                // Ignore
                System.err.println("Could not delete old output directory: " + e.getMessage());
            }

            // Create and start the consensus node manager
            final ConsensusNodeManager consensusNodeManager =
                    new ConsensusNodeManager(selfId, configuration, roster, version, keysAndCerts, backgroundExecutor);
            final CountDownLatch latch = new CountDownLatch(1);
            consensusNodeManager.registerPlatformStatusChangeListener(new Listener(latch));
            consensusNodeManager.start();

            System.out.println("Waiting for OtterNode to become ACTIVE");
            latch.await();
            System.out.println("OtterNode started successfully");

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while starting OtterNode", e);
        }
    }

    @NonNull
    private static Map<String, String> getOverriddenProperties(@NonNull final Path outputDirectory) {
        return Map.of(
                EventConfig_.EVENTS_LOG_DIR,
                outputDirectory.resolve("hgcapp").toString(),
                StateCommonConfig_.SAVED_STATE_DIRECTORY,
                outputDirectory.resolve("data/saved").toString(),
                FileSystemManagerConfig_.ROOT_PATH,
                outputDirectory.resolve("data").toString(),
                MetricsConfig_.CSV_OUTPUT_FOLDER,
                outputDirectory.resolve("data/stats").toString(),
                PathsConfig_.SETTINGS_USED_DIR,
                outputDirectory.toString(),
                PathsConfig_.KEYS_DIR_PATH,
                outputDirectory.resolve("data/keys").toString(),
                PathsConfig_.APPS_DIR_PATH,
                outputDirectory.resolve("data/apps").toString(),
                PathsConfig_.MARKER_FILES_DIR,
                outputDirectory.resolve("data/saved/marker_files").toString());
    }

    private record Listener(CountDownLatch latch) implements PlatformStatusChangeListener {
        @Override
        public void notify(final PlatformStatusChangeNotification data) {
            System.out.println("Platform status changed: " + data.getNewStatus());
            if (data.getNewStatus() == PlatformStatus.ACTIVE) {
                latch.countDown();
            }
        }
    }
}
