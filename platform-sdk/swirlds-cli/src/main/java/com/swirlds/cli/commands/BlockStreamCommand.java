// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.commands;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

/**
 * A collection of operations leveraging block streams.
 */
@CommandLine.Command(
        name = "block-stream",
        mixinStandardHelpOptions = true,
        description = "Operations leveraging block streams.")
@SubcommandOf(PlatformCli.class)
public final class BlockStreamCommand extends AbstractCommand {

    private BlockStreamCommand() {}
}
