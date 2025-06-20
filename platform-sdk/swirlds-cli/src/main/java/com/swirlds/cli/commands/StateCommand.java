// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.commands;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

/**
 * A collection of operations on states.
 */
@CommandLine.Command(name = "state", mixinStandardHelpOptions = true, description = "Operations on state files.")
@SubcommandOf(PlatformCli.class)
public final class StateCommand extends AbstractCommand {

    private StateCommand() {}
}
