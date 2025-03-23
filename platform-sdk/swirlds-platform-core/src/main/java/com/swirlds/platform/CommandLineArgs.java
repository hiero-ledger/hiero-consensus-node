// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.Set;
import org.hiero.consensus.model.node.NodeId;

/**
 * Command line arguments that can be passed to the browser when starting up
 *
 * @param localNodesToStart the set of nodes to start on this machine
 */
public record CommandLineArgs(@NonNull Set<NodeId> localNodesToStart) {
    /** The command line option to start a set of nodes on this machine */
    public static final String OPTION_LOCAL = "-local";

    /**
     * Parse the command line arguments passed to the main method
     *
     * @param args the arguments to parse
     * @return the parsed arguments
     */
    public static CommandLineArgs parse(@NonNull final String[] args) {
        // This set contains the nodes set by the command line to start.
        // If none are passed, then IP addresses will be compared to determine which node to start.
        final Set<NodeId> localNodesToStart = new HashSet<>();

        // Parse command line arguments (rudimentary parsing)
        String currentOption = null;
        for (final String item : args) {
            final String arg = item.trim().toLowerCase();
            if (arg.equals(OPTION_LOCAL)) {
                currentOption = arg;
            } else if (currentOption != null) {
                try {
                    localNodesToStart.add(NodeId.of(Integer.parseInt(arg)));
                } catch (final NumberFormatException ex) {
                    // Intentionally suppress the NumberFormatException
                }
            }
        }

        return new CommandLineArgs(localNodesToStart);
    }
}
