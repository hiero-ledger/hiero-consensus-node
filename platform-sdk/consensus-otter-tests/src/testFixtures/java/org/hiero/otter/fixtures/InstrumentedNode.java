// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Interface representing an instrumented node.
 *
 * <p>An instrumented node is a node that has additional instrumentation for testing purposes.
 * For example, it can exhibit malicious or erroneous behavior.
 */
public interface InstrumentedNode extends Node {

    /**
     * Ping the node with a message. All instrumented components should log the message.
     *
     * @param message the message to log
     */
    void ping(@NonNull String message);
}
