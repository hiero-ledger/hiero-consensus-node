// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.APP_NAME;
import static org.hiero.otter.fixtures.turtle.TurtleTestEnvironment.SWIRLD_NAME;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.NodeFileSystem;

class TurtleNodeFileSystem implements NodeFileSystem {
    private final SignedStateFilePath pathConfig;
    private final NodeId nodeId;

    TurtleNodeFileSystem(NodeId nodeId, Configuration configuration) {
        this.pathConfig = new SignedStateFilePath(configuration.getConfigData(StateCommonConfig.class));
        this.nodeId = nodeId;
    }

    @Override
    public @NonNull Path getLatestSnapshotStateDir() {
        final var states = pathConfig.getSavedStateFiles(APP_NAME, nodeId, SWIRLD_NAME);
        return states.getFirst().stateFile().getParent();
    }

    @Override
    public void useSnapshot(final @NonNull Path snapshotLocation) {

        final var savedStatesDir = pathConfig.getSignedStatesDirectoryForSwirld(APP_NAME, nodeId, SWIRLD_NAME);

        final var pcesSubdir = Path.of("preconsensus-events").resolve(nodeId.id() + "");
        final Path pcesSnapshotLocation = FileUtils.listFilesAndDirs(
                        FileUtils.listFilesAndDirs(snapshotLocation).getLast().resolve("preconsensus-events"))
                .getLast();
        try {
            FileUtils.copyDirectory(snapshotLocation, savedStatesDir);

            FileUtils.copyDirectory(
                    pcesSnapshotLocation,
                    pathConfig.getSignedStatesBaseDirectory().resolve(pcesSubdir));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
