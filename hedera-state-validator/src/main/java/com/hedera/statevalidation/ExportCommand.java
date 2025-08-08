// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import com.hedera.statevalidation.exporters.JsonExporter;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import java.io.IOException;
import picocli.CommandLine;

@CommandLine.Command(name = "export", description = "Exports the state")
public class ExportCommand implements Runnable {

    @CommandLine.ParentCommand
    private StateOperatorCommand parent;

    @CommandLine.Parameters(index = "0", arity = "0..1", description = "Service name")
    private String serviceName;

    @CommandLine.Parameters(index = "1", arity = "0..1", description = "State name")
    private String stateName;

    @Override
    public void run() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());

        MerkleNodeState state;
        try {
            DeserializedSignedState deserializedSignedState = StateResolver.initState();
            state = deserializedSignedState.reservedSignedState().get().getState();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        final JsonExporter exporter = new JsonExporter(state, serviceName, stateName);
        exporter.export();
    }
}
