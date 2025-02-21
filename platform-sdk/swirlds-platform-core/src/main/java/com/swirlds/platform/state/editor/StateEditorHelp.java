// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.editor;

import com.swirlds.cli.utility.CommandBuilder;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

@CommandLine.Command(
        name = "help",
        aliases = {"h"},
        helpCommand = true,
        description = "Show the state editor usage information.")
@SubcommandOf(StateEditorRoot.class)
public class StateEditorHelp extends StateEditorOperation {

    @CommandLine.Spec
    private CommandLine.Model.CommandSpec spec;

    @Override
    @SuppressWarnings("java:S106")
    public void run() {
        spec.commandLine().getParent().usage(System.out, CommandBuilder.getColorScheme());
    }
}
