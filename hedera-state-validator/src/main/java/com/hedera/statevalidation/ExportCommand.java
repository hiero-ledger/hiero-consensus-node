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
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

@Command(name = "export", description = "Exports the state.")
public class ExportCommand implements Runnable {

    private static final Logger log = LogManager.getLogger(ExportCommand.class);

    @ParentCommand
    private StateOperatorCommand parent;

    @Parameters(index = "0", arity = "1", description = "Result directory.")
    private String resultDirStr;

    @Parameters(index = "1", arity = "0..1", description = "Service name.")
    private String serviceName;

    @Parameters(index = "2", arity = "0..1", description = "State key.")
    private String stateKey;

    @Override
    public void run() {
        parent.initializeStateDir();
        final File resultDir = new File(resultDirStr);
        if (!resultDir.exists()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " does not exist");
        }
        if (!resultDir.isDirectory()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " is not a directory");
        }

        final MerkleNodeState state;
        log.debug("Initializing the state...");
        long start = System.currentTimeMillis();
        try {
            DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.debug("State has been initialized in {} seconds.", (System.currentTimeMillis() - start) / 1000);

        ((VirtualMap) state.getRoot()).getDataSource().stopAndDisableBackgroundCompaction();

        final JsonExporter exporter = new JsonExporter(resultDir, state, serviceName, stateKey);
        exporter.export();
        log.debug("Total time is {} seconds.", (System.currentTimeMillis() - start) / 1000);
    }
}
