// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.commands;

import com.swirlds.cli.PlatformCli;
import com.swirlds.cli.utility.AbstractCommand;
import com.swirlds.cli.utility.SubcommandOf;
import picocli.CommandLine;

/**
 * A collection of operations on event streams.
 */
@CommandLine.Command(
        name = "event-stream",
        mixinStandardHelpOptions = true,
        description = "Operations on event streams.")
@SubcommandOf(PlatformCli.class)
public final class EventStreamCommand extends AbstractCommand {

    private EventStreamCommand() {}
}
