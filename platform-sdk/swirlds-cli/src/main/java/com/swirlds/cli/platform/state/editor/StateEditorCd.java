// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

@CommandLine.Command(
        name = "cd",
        mixinStandardHelpOptions = true,
        description = "Change the current working route. Analogous to the cd in bash.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorCd extends StateEditorOperation {

    private String path = "/";

    @CommandLine.Parameters(arity = "0..1", description = "The route to change to.")
    private void setPath(final String path) {
        this.path = path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
        getStateEditor().setCurrentWorkingRoute(getStateEditor().getRelativeRoute(path));
    }
}
