// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.roster.test.fixtures;

import com.hedera.hapi.node.state.roster.Roster;
import java.util.Collections;
import java.util.Map;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

/**
 * A {@link Roster} bundled with the cryptographic keys and certificates for each node. Produced by
 * {@link RandomRosterBuilder#buildWithKeys()} when real key generation is enabled.
 */
public class RosterWithKeys {
    private final Roster roster;
    /**
     * This map holds the private signing keys for each roster entry.
     */
    private final Map<NodeId, KeysAndCerts> privateKeys;

    /**
     * Creates a new {@code RosterWithKeys}.
     *
     * @param roster      the roster
     * @param privateKeys the cryptographic keys and certificates for each node in the roster
     */
    public RosterWithKeys(final Roster roster, final Map<NodeId, KeysAndCerts> privateKeys) {
        this.roster = roster;
        this.privateKeys = Collections.unmodifiableMap(privateKeys);
    }

    /**
     * Returns the roster.
     *
     * @return the roster
     */
    public Roster getRoster() {
        return roster;
    }

    /**
     * Returns the keys and certificates for the given node.
     *
     * @param nodeId the node ID to look up
     * @return the keys and certificates for the node
     * @throws IllegalArgumentException if no keys are found for the given node ID
     */
    public KeysAndCerts getKeysAndCerts(final NodeId nodeId) {
        final KeysAndCerts kac = privateKeys.get(nodeId);
        if (kac == null) {
            throw new IllegalArgumentException("No KeysAndCerts found for node ID: " + nodeId);
        }
        return kac;
    }

    /**
     * Returns an unmodifiable map of all node IDs to their keys and certificates.
     *
     * @return all keys and certificates
     */
    public Map<NodeId, KeysAndCerts> getAllKeysAndCerts() {
        return privateKeys;
    }
}
