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

    // Big-endian write long to any array
    static void write( byte[] dst, int off, long x ) {
        for( int i=0; i<8; i++ ) {
            dst[off + 7-i] = (byte)x;
            x >>= 8;
        }
    }

    // Big-endian write 4xlongs
    void write( int adr, long x0, long x1, long x2, long x3 ) {
        assert (adr&31)==0;     // 32b aligned, caller already checked
        growMem( adr+32 );
        /// Big-endian write
        write(_mem, adr   , x3);
        write(_mem, adr+ 8, x2);
        write(_mem, adr+16, x1);
        write(_mem, adr+24, x0);
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

    // Pretty print
    static StringBuilder hex( int s, StringBuilder sb ) {
        int digit = (s>>4) & 0xf;
        sb.append((char)((digit <= 9 ? '0' : ('A'-10))+digit));
        digit = s & 0xf;
        sb.append((char)((digit <= 9 ? '0' : ('A'-10))+digit));
        return sb;
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[[\n");
        for( int i=0; i<_len; i+=32 ) {
            sb.append(" ");
            hex(i>>24,sb);
            hex(i>>16,sb);
            hex(i>> 8,sb);
            hex(i    ,sb);
            sb.append(": 0x");
            for( int j=i; j<i+32; j++ )
                hex(_mem[j],sb);
            sb.append("\n");
        }
        return sb.append("]\n").toString();
    }
}
