// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.node.app.service.contract.impl.utils.TODO;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// Utility for interning UInt256 from parts

// UI256's are only made here, and are always interned so it is safe and
// expected (and fast) to simply do pointer equality checks.
public class UI256 {

    // The interning table
    private static final ConcurrentHashMap<UI256,UInt256> INTERN = new ConcurrentHashMap<>();

    private long _x0, _x1, _x2, _x3;
    private int _hash;

    // Private no-arg constuctor, only used internally for interning
    private UI256( ) {}
    private UI256 init(long x0, long x1, long x2, long x3 ) {
        _x0=x0; _x1=x1; _x2=x2; _x3=x3; _hash = (int)(x0 ^ x1 ^ x2 ^ x3);
        return this;
    }
    @Override public boolean equals( Object o ) {
        return o instanceof UI256 u && _hash==u._hash &&
            _x0==u._x0 && _x1==u._x1 && _x2==u._x2 && _x3==u._x3;
    }
    @Override public int hashCode() { return _hash; }

    // A single thread-local mutable UI256, used to probe the INTERN table
    // without allocating on a hit... the expected case.
    private static final ThreadLocal<UI256> FREE = new ThreadLocal<>();
    private static UI256 make( long x0, long x1, long x2, long x3 ) {
        UI256 u = FREE.get();
        FREE.set(null);
        return (u==null ? new UI256() : u).init(x0,x1,x2,x3);
    }
    private UInt256 free(UInt256 v) { assert FREE.get()==null; FREE.set(this); return v; }

    static UInt256 intern( long x0, long x1, long x2, long x3 ) {
        // Small values are already interned
        if( x1==0 && x2==0 && x3==0 && 0 <= x0 && x0 < 64 )
            return UInt256.valueOf(x0);
        // Make a temp/mutable UI256, mostly without allocation
        UI256 k = make(x0,x1,x2,x3);
        // Probe the table once
        UInt256 v = INTERN.get(k);
        if( v != null ) return k.free(v);
        // Build a UInt256
        byte[] bs = new byte[32];
        Memory.write8(bs,24,x0);
        Memory.write8(bs,16,x1);
        Memory.write8(bs, 8,x2);
        Memory.write8(bs, 0,x3);
        v = UInt256.fromBytes( Bytes.wrap(bs));
        // Race to insert
        UInt256 v2 = INTERN.putIfAbsent(k,v);
        return v2==null
            ? v   // Won the race; `k` is now part of the table, do not free(k)
            : k.free(v2);       // Lost the race, return existing value
    }

    // Get 1 of 4 longs out of a UInt26.  Utility to support a long-striped
    // stack in Bonneville.
    static long getLong( UInt256 u, int idx ) {
        long x = 0;
        for( int i=0; i<8; i++ )
            x |= ((long)(u.get( (idx<<3)+i )&0xFF)) << ((7-i)<<3);
        return x;
    }

    // A Supplier for BESU libs.  Used to make 1-shot wrapped UInt256's that do
    // not allocate.
    public static class Wrap implements Supplier<UInt256> {
        UInt256 _u;
        @Override public UInt256 get() { return _u; }
    }

}
