// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import com.swirlds.platform.roster.RosterUtils;
import org.hiero.consensus.model.node.NodeId;

/**
 * Denotes which of the three purposes a key or certificate serves
 */
public enum KeyCertPurpose {
    SIGNING("s"),
    AGREEMENT("a");

    /** the prefix used for certificate names */
    private final String prefix;

    KeyCertPurpose(final String prefix) {
        this.prefix = prefix;
    }

    /**
     * @param nodeId the node identifier
     * @return the name of the key or certificate used in a KeyStore for this member and key type
     */
    public String storeName(final NodeId nodeId) {
        return prefix + "-" + RosterUtils.formatNodeName(nodeId);
    }

    /**
     * Returns the prefix associated with a key or certificate purpose.
     *
     * @return the prefix.
     */
    public String prefix() {
        return prefix;
    }
}
