// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.commands;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

/**
 * A collection of operations on log files.
 */
@CommandLine.Command(name = "log", mixinStandardHelpOptions = true, description = "Operations on log files.")
@SubcommandOf(PlatformCli.class)
public final class LogCommand extends AbstractCommand {

    private LogCommand() {}
}
