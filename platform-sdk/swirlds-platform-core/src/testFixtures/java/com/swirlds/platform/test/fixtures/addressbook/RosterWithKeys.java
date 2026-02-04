// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.addressbook;

import com.hedera.hapi.node.state.roster.Roster;
import java.util.Collections;
import java.util.Map;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;

public class RosterWithKeys {
    private final Roster roster;
    /**
     * This map holds the private signing keys for each roster entry.
     */
    private final Map<NodeId, KeysAndCerts> privateKeys;

    public RosterWithKeys(final Roster roster, final Map<NodeId, KeysAndCerts> privateKeys) {
        this.roster = roster;
        this.privateKeys = Collections.unmodifiableMap(privateKeys);
    }

    public Roster getRoster() {
        return roster;
    }

    public KeysAndCerts getKeysAndCerts(final NodeId nodeId) {
        final KeysAndCerts kac = privateKeys.get(nodeId);
        if (kac == null) {
            throw new IllegalArgumentException("No KeysAndCerts found for node ID: " + nodeId);
        }
        return kac;
    }

    public Map<NodeId, KeysAndCerts> getAllKeysAndCerts() {
        return privateKeys;
    }
}
