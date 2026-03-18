// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pcli;

import picocli.CommandLine;

/**
 * A collection of operations on states.
 */
@CommandLine.Command(name = "state", mixinStandardHelpOptions = true, description = "Operations on state files.")
@SubcommandOf(Pcli.class)
public final class StateCommand extends AbstractCommand {

    private StateCommand() {}
}
