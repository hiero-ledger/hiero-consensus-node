// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

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

    static AdrKey intern(TopXTN top, Address adr, UInt256 key) {
        for( AdrKey adrkey : top._warmAdrStk )
            if( adrkey._adr == adr && adrkey._key == key )
                return adrkey;
        AdrKey ak = new AdrKey(adr,key);
        top._warmAdrStk.add(ak);
        return ak;
    }

    private AdrKey(Address adr, UInt256 key) { _adr=adr; _key=key; }
    private final Address _adr; // The pair (Address,UInt256) are a Key for the warm/cold check
    private final UInt256 _key; // Can be null
    boolean _warm; // Warm-up flag
}
// spotless:on
