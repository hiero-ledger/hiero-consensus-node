// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.exporter.SortedDiffExporter;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.state.merkle.VirtualMapState;
import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jspecify.annotations.NonNull;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** Compares two states and writes a sorted diff grouped by service and state key. */
@Command(name = "sorted-diff", description = "Compares two states and creates a sorted, grouped diff.")
public class SortedDiffCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(SortedDiffCommand.class);
    private static final String STATE_1 = "STATE1";
    private static final String STATE_2 = "STATE2";

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
            description = "Resulting diff directory path. Must exist before invocation.")
    private String outputDirStr;

    @Override
    public void run() {
        final File outputDirectory = new File(outputDirStr);
        if (!outputDirectory.exists()) {
            throw new RuntimeException(outputDirectory.getAbsolutePath() + " does not exist");
        }
        if (!outputDirectory.isDirectory()) {
            throw new RuntimeException(outputDirectory.getAbsolutePath() + " is not a directory");
        }

        log.info("Initializing the first state...");
        parent.initializeStateDir();
        System.setProperty("tmp.dir", getTmpDir(parent.getStateDir()));
        long start = System.currentTimeMillis();
        final VirtualMapState state1 = StateUtils.getState(STATE_1);
        log.info("First state has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        log.info("Initializing the second state...");
        System.setProperty("state.dir", stateDir2.getAbsolutePath());
        System.setProperty("tmp.dir", getTmpDir(stateDir2));
        start = System.currentTimeMillis();
        final VirtualMapState state2 = StateUtils.getState(STATE_2);
        log.info("Second state has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        final SortedDiffExporter exporter = (serviceName == null)
                ? new SortedDiffExporter(outputDirectory, state1, state2, StateUtils.prepareServiceNamesAndStateKeys())
                : new SortedDiffExporter(outputDirectory, state1, state2, serviceName, stateKey);
        exporter.export();
    }

    private @NonNull String getTmpDir(final File parentDir) {
        return parentDir.toPath().resolve("tmp").toFile().getAbsolutePath();
    }
}
