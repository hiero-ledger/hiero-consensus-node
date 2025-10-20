// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.exporter.JsonExporter;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@Command(name = "export", description = "Exports the state.")
public class ExportCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(ExportCommand.class);

    @ParentCommand
    private StateOperatorCommand parent;

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
            description = "Directory where the exported JSON files are written. Must exist before invocation.")
    private String outputDirStr;

    @Override
    public void run() {
        parent.initializeStateDir();
        final File outputDir = new File(outputDirStr);
        if (!outputDir.exists()) {
            throw new RuntimeException(outputDir.getAbsolutePath() + " does not exist");
        }
        if (!outputDir.isDirectory()) {
            throw new RuntimeException(outputDir.getAbsolutePath() + " is not a directory");
        }

        final MerkleNodeState state;
        log.debug("Initializing the state...");
        long start = System.currentTimeMillis();
        try {
            final DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.debug("State has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        ((VirtualMap) state.getRoot()).getDataSource().stopAndDisableBackgroundCompaction();

        final JsonExporter exporter = new JsonExporter(outputDir, state, serviceName, stateKey);
        exporter.export();
        log.debug("Total time is {} seconds.", (System.currentTimeMillis() - start) / 1000);
    }
}
