// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

/**
 * Meow.
 */
@CommandLine.Command(name = "cat", mixinStandardHelpOptions = true, description = "Print the toString() of a node")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorCat extends StateEditorOperation {

    private String path = "";

    @CommandLine.Parameters(arity = "0..1", description = "The target route.")
    private void setPath(final String path) {
        this.path = path;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("java:S106")
    public void run() {
        System.out.println(getStateEditor().getRelativeNode(path));
    }
}
