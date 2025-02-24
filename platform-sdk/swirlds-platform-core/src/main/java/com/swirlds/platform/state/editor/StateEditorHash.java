// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import com.swirlds.common.merkle.crypto.MerkleCryptoFactory;
import com.swirlds.platform.state.signed.ReservedSignedState;
import java.util.concurrent.ExecutionException;
import picocli.CommandLine;

@CommandLine.Command(name = "hash", mixinStandardHelpOptions = true, description = "Hash unhashed nodes the state.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorHash extends StateEditorOperation {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        try (final ReservedSignedState reservedSignedState = getStateEditor().getState("StateEditorHash.run()")) {
            MerkleCryptoFactory.getInstance()
                    .digestTreeAsync(reservedSignedState.get().getState().getRoot())
                    .get();
        } catch (final InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
