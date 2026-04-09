// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import picocli.CommandLine;

/**
 * A collection of operations on event streams.
 */
@CommandLine.Command(
        name = "event-stream",
        mixinStandardHelpOptions = true,
        description = "Operations on event streams.")
@SubcommandOf(Pcli.class)
public final class EventStreamCommand extends AbstractCommand {

    private EventStreamCommand() {}
}
