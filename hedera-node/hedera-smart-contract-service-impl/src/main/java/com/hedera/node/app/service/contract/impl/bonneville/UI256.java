// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import org.apache.tuweni.units.bigints.UInt256;
import com.hedera.node.app.service.contract.impl.utils.TODO;

import java.util.function.Supplier;

// Utility for interning UInt256 from parts
public abstract class UI256 {

    public static class Wrap implements Supplier<UInt256> {
        UInt256 _u;
        @Override public UInt256 get() { return _u; }
    }


    static UInt256 intern( long x0, long x1, long x2, long x3 ) {
        if( x1==0 && x2==0 && x3==0 ) {
            if( 0 <= x0 && x0 < 64 )
                return UInt256.valueOf(x0);
        }
        throw new TODO();
    }


    static long getLong( UInt256 u, int idx ) {
        long x = 0;
        for( int i=0; i<8; i++ )
            x |= u.get( (idx<<3)+i ) << ((7-i)<<3);
        return x;
    }


}
