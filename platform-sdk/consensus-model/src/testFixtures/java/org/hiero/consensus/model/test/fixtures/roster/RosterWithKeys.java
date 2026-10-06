// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.test.fixtures.roster;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;

/**
 * A {@link RosterWrapper} bundled with the cryptographic keys and certificates for each node. Produced by
 * {@link RosterWrapperFactory#randomRosterWithKeys}.
 *
 * @param roster the roster
 * @param privateKeys a map of node IDs to their corresponding keys and certificates
 */
public record RosterWithKeys(
        @NonNull RosterWrapper roster, @NonNull Map<NodeId, KeysAndCerts> privateKeys) {

    /**
     * Returns the keys and certificates for the given node.
     *
     * @param nodeId the node ID to look up
     * @return the keys and certificates for the node
     * @throws IllegalArgumentException if no keys are found for the given node ID
     */
    @NonNull
    public KeysAndCerts privateKey(@NonNull final NodeId nodeId) {
        final KeysAndCerts keysAndCerts = privateKeys.get(nodeId);
        if (keysAndCerts == null) {
            throw new IllegalArgumentException("No KeysAndCerts found for node ID: " + nodeId);
        }
        return keysAndCerts;
    }
}
