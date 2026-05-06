// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import java.util.function.Supplier;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

// Utility for interning UInt256 from parts.  The goal here is to cut down the
// number of allocated UInt256's in the EVM, by re-using whenever possible.

// Because they are interned, they can be compared with a plain pointer check.
// The rest of the EVM can produce non-interned UInt256's, and so you cannot do
// the compare trick on "other" UInt256's.  The only place doing this trick is
// the AdrKey code, which DOES take care that all UInt256's it sees are
// interned, and is reasonably hot so has some pay-off for this trick.

// A brief run of HeliSwap shows that we get roughly 4 touches per UInt256 -
// one to make it, and 3 more hits afterward.  Then the UIn256 goes dead,
// probably just another hash in an endless stream of hashes.  There is no win
// in hanging onto lots of these hashes, so we want to limit the intern table
// size.  Using a small ring-buffer, should catch the immediate reuses but
// never grows.


// spotless:off
public class UI256 {

    private static final int LOG = 4; // Power-of-2 sized ring buffer
    private int _idx;           // Ring buffer index
    private final UInt256[] _u256; // The UInt256 mapping to these longs
    private final long[] _x0, _x1, _x2, _x3; // 256bits


    private UI256() {
        _u256 = new UInt256[1<<LOG];
        _x0 = new long[1<<LOG];
        _x1 = new long[1<<LOG];
        _x2 = new long[1<<LOG];
        _x3 = new long[1<<LOG];
    }

    // A collection of thread-local recent UInt256's
    private static final ThreadLocal<UI256> INTERN = new ThreadLocal<>();

    static UInt256 intern(long x0, long x1, long x2, long x3) {
        // Small values are already interned
        if( x1 == 0 && x2 == 0 && x3 == 0 && 0 <= x0 && x0 < 64 )
            return UInt256.valueOf(x0);
        // Check thread-local ring buffer of recent UInt256's
        UI256 ui = INTERN.get();
        if( ui==null ) INTERN.set(ui = new UI256());
        return ui.intern2(x0,x1,x2,x3);
    }

    UInt256 intern2(long x0, long x1, long x2, long x3) {
        // Hunt the ring buffer for a hit
        for( int i=0; i<(1<<LOG); i++ )
            if( _x0[i]==x0 && _x1[i]==x1 && _x2[i]==x2 && _x3[i]==x3 )
                return _u256[i];
        // Miss, gotta make one
        _x0[_idx] = x0;
        _x1[_idx] = x1;
        _x2[_idx] = x2;
        _x3[_idx] = x3;
        // Build a UInt256
        byte[] bs = new byte[32];
        Memory.write8(bs, 24, x0);
        Memory.write8(bs, 16, x1);
        Memory.write8(bs,  8, x2);
        Memory.write8(bs,  0, x3);
        // Wildly inefficent UIn256 constructor path
        UInt256 ui = _u256[_idx] = UInt256.fromBytes(Bytes.wrap(bs));
        // Ring buffer index bump
        _idx = (_idx+1) & ((1<<LOG)-1);
        return ui;
    }

    // Get 1 of 4 longs out of a UInt26.  Utility to support a long-striped
    // stack in Bonneville.
    static long getLong(UInt256 u, int idx) {
        long x = 0;
        for( int i = 0; i < 8; i++ )
            x |= ((long) (u.get((idx << 3) + i) & 0xFF)) << ((7 - i) << 3);
        return x;
    }

    // A Supplier for BESU libs.  Used to make 1-shot wrapped UInt256's that do
    // not allocate.
    public static class Wrap implements Supplier<UInt256> {
        UInt256 _u;
        @Override  public UInt256 get() { return _u; }
    }
}
// spotless:on
