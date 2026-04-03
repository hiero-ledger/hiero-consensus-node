// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import java.util.HashMap;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;

// Storage "slots" are keyed by (Address,UInt256) and are cold until first
// touched.  "cold" is reset at top-level contracts, and "warm" is passed down
// to all child contract calls.  Reverted contracts undo their "warming"
// touches as if they never happened.

// Utility for interning AdrKey from parts.  The goal here is to cut down the
// number of allocated AdrKey's in the EVM, by re-using whenever possible.
// AdrKeys are interned per-thread.

// Because they are interned, they can be compared with a plain pointer check.
// spotless:off
public class AdrKey {

    // Thread-local class, not-race-safe.
    public static class AdrKeyIntern {
        // The interning table
        private final HashMap<AdrKey, AdrKey> _intern = new HashMap<>();
        // Free list
        private AdrKey _free = new AdrKey();

        public AdrKey get(Address adr, UInt256 key) {
            // Load up fields from the one free AdrKey
            _free._adr = adr;
            _free._key = key;
            _free._hash = adr.hashCode() ^ (key == null ? -1 : key.hashCode());
            // Probe the table
            AdrKey v = _intern.get(_free);
            if( v != null )
                // Hit!  Return the already interned
                return v;
            // Intern the free AdrKey (making it not-free)
            _intern.put( v = _free, v );
            _free = new AdrKey(); // Setup for next intern probe
            return v;
        }

        public boolean allColdSlots() {
            for( AdrKey ak : _intern.keySet() ) if( ak._warm ) return false;
            return true;
        }
    }

    private Address _adr; // The pair (Address,UInt256) are a Key for the warm/cold check
    private UInt256 _key; // Can be null
    boolean _warm; // Warm-up flag
    private int _hash;

    // Private no-arg constructor, only used internally for interning
    private AdrKey() {}

    @Override
    public boolean equals(Object o) {
        return o instanceof AdrKey ak
                // Equality check uses the interned property of UInt256 to do a
                // cheap equality check
                && _key == ak._key
                && _adr.equals(ak._adr);
    }

    @Override public int hashCode() { return _hash; }
}
// spotless:on
