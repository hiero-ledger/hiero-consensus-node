// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.compaction.Compaction.runCompaction;

import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import picocli.CommandLine;

@CommandLine.Command(name = "compact", description = "Performs compaction of state files.")
public class CompactionCommand implements Runnable {

    @CommandLine.ParentCommand
    private StateOperatorCommand parent;

    private CompactionCommand() {}

    @Override
    public void run() {
        System.setProperty("state.dir", parent.getStateDir().getAbsolutePath());

        try {
            final DeserializedSignedState deserializedSignedState = StateResolver.initState();
            final MerkleNodeState merkleNodeState =
                    deserializedSignedState.reservedSignedState().get().getState();
            runCompaction(merkleNodeState);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
