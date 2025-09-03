// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.platform.state.editor;

import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

@CommandLine.Command(
        name = "clear",
        mixinStandardHelpOptions = true,
        description = "Write a bunch of newlines to the console.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorClear extends StateEditorOperation {

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("java:S106")
    public void run() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }
}
