// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.exporters.JsonExporter;
import com.hedera.statevalidation.exporters.SortedJsonExporter;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import java.io.File;
import java.io.IOException;
import picocli.CommandLine;

@CommandLine.Command(name = "export", description = "Exports the state")
public class ExportCommand implements Runnable {

    public static final int MAX_OBJ_PER_FILE = Integer.parseInt(System.getProperty("maxObjPerFile", "1000000"));
    public static final boolean PRETTY_PRINT_ENABLED = Boolean.parseBoolean(System.getProperty("prettyPrint", "false"));

    @CommandLine.ParentCommand
    private StateOperatorCommand parent;

    @CommandLine.Parameters(index = "0", arity = "1", description = "Result directory")
    private String resultDirStr;

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "Service name")
    private String serviceName;

    @CommandLine.Parameters(index = "2", arity = "0..1", description = "State name")
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
        try {
            DeserializedSignedState deserializedSignedState = StateResolver.initState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        boolean sorted = Boolean.parseBoolean(System.getProperty("sorted", "false"));
        if (sorted) {
            final SortedJsonExporter exporter = new SortedJsonExporter(resultDir, state, serviceName, stateName);
            exporter.export();
        } else {
            final JsonExporter exporter = new JsonExporter(resultDir, state, serviceName, stateName);
            exporter.export();
        }
    }
}
