// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import org.apache.tuweni.bytes.Bytes;

import java.util.Arrays;

// Bonneville memory.

// Tracks size in units of Words (32bytes).
// Must support arbitrary byte-array copies.
// Must support some form of 32byte reads/writes.

public class Memory {

    int _len = 0;               // Active, or in-use bytes.  Rounded to 32bytes
    byte[] _mem=new byte[32];   // Raw bytes

    long read( int adr ) {
        assert (adr&7)==0;      // 8b aligned, caller already checked
        return adr >= _mem.length ? 0 : read8(_mem,adr);
    }


    // Grow to handle len bytes
    private void growMem( int len ) {
        len = (len+31) & -32;   // Round up to nearest 32
        // Grow in-use bytes
        if( len < _len ) return;
        _len = len;
        if( len < _mem.length  ) return;

        // If memory is too small, grow it
        int newlen = _mem.length;
        while( len > newlen )
            newlen <<= 1;   // Next larger power-of-2
        _mem = Arrays.copyOf(_mem, newlen);
    }

    // Big-endian read long from any array
    static long read8( byte[] src, int off ) {
        long x = 0;
        for( int i=0; i<8; i++ ) {
            x <<= 8;
            x |= src[off + i] & 0xFF;
        }
        return x;
    }

    // Big-endian write long to any array, any offset
    static void write8( byte[] dst, int off, long x ) {
        for( int i=0; i<8; i++ ) {
            dst[off + 7-i] = (byte)x;
            x >>= 8;
        }
    }

    // Big-endian write 4xlongs
    void write( int adr, long x0, long x1, long x2, long x3 ) {
        growMem( adr+32 );
        // Big-endian write
        write8(_mem, adr   , x3);
        write8(_mem, adr+ 8, x2);
        write8(_mem, adr+16, x1);
        write8(_mem, adr+24, x0);
    }

    // Array write.  Caller checks args are positive.
    // `soff+len` is allowed to be larger than `src.length`;
    // in which case the extra is zero-filled.
    void write( int doff, byte[] src, int soff, int len ) {
        growMem(doff+len);
        int copyLen = Math.min(len,src.length-soff);
        System.arraycopy(src,soff,_mem,doff,copyLen);
        if( copyLen < len ) Arrays.fill(_mem,doff+copyLen,len-copyLen,(byte)0);
    }

    // Just wrap as a Bytes array
    Bytes asBytes(int off, int len) {
        return Bytes.wrap(_mem,off,len);
    }

    @Override public String toString() {
        SB sb = new SB().p("[[\n");
        for( int i=0; i<_len; i+=32 ) {
            sb.p(" ").hex4(i).p(": 0x");
            for( int j=i; j<i+32; j++ )
                sb.hex1(_mem[j]);
            sb.p("\n");
        }
        return sb.p("]]\n").toString();
    }
}
