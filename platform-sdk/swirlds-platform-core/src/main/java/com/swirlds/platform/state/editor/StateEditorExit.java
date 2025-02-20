// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

@CommandLine.Command(name = "exit", mixinStandardHelpOptions = true, description = "Exit the state editor.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorExit extends StateEditorOperation {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        getStateEditor().exit();
    }
}
