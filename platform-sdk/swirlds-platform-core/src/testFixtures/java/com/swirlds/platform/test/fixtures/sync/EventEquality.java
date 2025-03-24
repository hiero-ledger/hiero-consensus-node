// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import com.swirlds.platform.internal.EventImpl;
import org.hiero.consensus.model.crypto.Hash;

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
    public static boolean identicalHashes(final EventImpl a, final EventImpl b) {
        return (a == null && b == null) || a.getBaseHash().equals(b.getBaseHash());
    }

    /**
     * Equality of two events by hash. If the events are both null, they are considered equal.
     */
    public static boolean identicalHashes(final Hash ha, final Hash hb) {
        return (ha == null && hb == null) || ha.equals(hb);
    }
}
