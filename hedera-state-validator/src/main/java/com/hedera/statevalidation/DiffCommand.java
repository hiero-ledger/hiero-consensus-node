// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.exporter.DiffExporter;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "diff", description = "Compares two states and creates a diff.")
public class DiffCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(DiffCommand.class);

    @CommandLine.ParentCommand
    private StateOperatorCommand parent;

    @Parameters(index = "0", description = "Second state directory.")
    private File stateDir2;

    @Option(
            names = {"-s", "--service-name"},
            description = "Service name.")
    private String serviceName;

    @Option(
            names = {"-k", "--state-key"},
            description = "State key.")
    private String stateKey;

    @Option(
            names = {"-o", "--out"},
            required = true,
            description = "Resulting diff directory path.")
    private String outputDirStr;

    @Override
    public void run() {
        final File outputDirectory = new File(outputDirStr);
        if (!outputDirectory.exists()) {
            throw new RuntimeException(outputDirectory.getAbsolutePath() + " does not exist");
        }
        log.debug("Initializing the first state...");
        parent.initializeStateDir();
        System.setProperty("tmp.dir", getTmpDir(parent.getStateDir()));
        long start = System.currentTimeMillis();
        final MerkleNodeState state1 = StateUtils.getState("STATE1");
        ((VirtualMap) state1.getRoot()).getDataSource().stopAndDisableBackgroundCompaction();
        log.debug("First state has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        log.debug("Initializing the second state...");
        System.setProperty("state.dir", stateDir2.getAbsolutePath());
        System.setProperty("tmp.dir", getTmpDir(stateDir2));
        start = System.currentTimeMillis();
        final MerkleNodeState state2 = StateUtils.getState("STATE2");
        ((VirtualMap) state2.getRoot()).getDataSource().stopAndDisableBackgroundCompaction();
        log.debug("Second state has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        final DiffExporter exporter = new DiffExporter(outputDirectory, state1, state2, serviceName, stateKey);
        exporter.export();
    }

    private @NonNull String getTmpDir(File parentDir) {
        return parentDir.toPath().resolve("tmp").toFile().getAbsolutePath();
    }
}
