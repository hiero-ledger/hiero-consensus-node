// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.test.fixtures.sync;

import org.hiero.base.crypto.Hash;

/**
 * Utility types to define equality of events, sets of shadow events and hashes.
 */
public final class EventEquality {

    /**
     * Private ctor. This is a utility class.
     */
    private EventEquality() {
        // This ctor does nothing
    }

    /**
     * Equality of two events by hash. If the events are both null, they are considered equal.
     */
    public static boolean identicalHashes(final Hash ha, final Hash hb) {
        return (ha == null && hb == null) || ha.equals(hb);
    }
}
