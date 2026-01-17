// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.compaction.Compaction.runCompaction;

import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;

@Command(name = "compact", description = "Performs compaction of state files.")
public class CompactionCommand implements Runnable {

    @ParentCommand
    private StateOperatorCommand parent;

    private CompactionCommand() {}

    @Override
    public void run() {
        parent.initializeStateDir();
        try {
            final DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
            final MerkleNodeState merkleNodeState =
                    deserializedSignedState.reservedSignedState().get().getState();
            runCompaction(merkleNodeState);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
