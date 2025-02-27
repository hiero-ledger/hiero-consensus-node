// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.utility.MerkleUtils;
import com.swirlds.platform.state.signed.ReservedSignedState;
import picocli.CommandLine;

@CommandLine.Command(
        name = "rehash",
        mixinStandardHelpOptions = true,
        description = "Recompute the hash for the state.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorRehash extends StateEditorOperation {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorRehash.run()")) {
            MerkleUtils.rehashTree(reservedSignedState.get().getState().getRoot());
        }
    }
}
