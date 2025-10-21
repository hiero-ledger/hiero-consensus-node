// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static picocli.CommandLine.*;

import com.hedera.statevalidation.exporters.JsonExporter;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import java.io.File;

@Command(name = "export", description = "Exports the state.")
public class ExportCommand implements Runnable {

    public static final int MAX_OBJ_PER_FILE = Integer.parseInt(System.getProperty("maxObjPerFile", "1000000"));
    public static final boolean PRETTY_PRINT_ENABLED = Boolean.parseBoolean(System.getProperty("prettyPrint", "false"));

    @ParentCommand
    private StateOperatorCommand parent;

    @Parameters(index = "0", arity = "1", description = "Result directory")
    private String resultDirStr;

    @Parameters(index = "1", arity = "0..1", description = "Service name")
    private String serviceName;

    @Parameters(index = "2", arity = "0..1", description = "State name")
    private String stateName;

    @Override
    public void run() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());
        File resultDir = new File(resultDirStr);
        if (!resultDir.exists()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " does not exist");
        }
        if (!resultDir.isDirectory()) {
            throw new RuntimeException(resultDir.getAbsolutePath() + " is not a directory");
        }

        MerkleNodeState state;
        System.out.println("Initializing the state");
        long start = System.currentTimeMillis();
        try {
            DeserializedSignedState deserializedSignedState = StateResolver.initState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        System.out.printf("State has been initialized in %d seconds. \n", (System.currentTimeMillis() - start) / 1000);

        ((VirtualMap) state.getRoot()).getDataSource().stopAndDisableBackgroundCompaction();

        final JsonExporter exporter = new JsonExporter(resultDir, state, serviceName, stateName);
        exporter.export();

        System.out.printf("Total time is  %d seconds. \n", (System.currentTimeMillis() - start) / 1000);
    }
}
