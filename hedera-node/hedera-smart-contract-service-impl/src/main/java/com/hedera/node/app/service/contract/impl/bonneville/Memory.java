// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.node.app.service.contract.impl.utils.TODO;

import java.util.Arrays;

import org.apache.tuweni.bytes.Bytes;

// Bonneville memory.

// Tracks size in units of Words (32bytes).
// Must support arbitrary byte-array copies.
// Must support some form of 32byte reads/writes.

public class Memory {

    int _len = 0;               // Active, or in-use bytes.  Rounded to 32bytes
    byte[] _mem=new byte[32];   // Raw bytes

    public void reset() {
        _len = 0;
        Arrays.fill(_mem,(byte)0);
    }


    long read( int adr ) {
        return adr >= _mem.length ? 0 : read8(_mem,adr);
    }


    // Grow to handle len bytes
    // TODO: Only grow backing store on non-zero WRITES.
    void growMem( int len ) {
        len = (len+31) & -32;   // Round up to nearest 32
        // Grow in-use bytes
        if( len <= _len ) return;
        _len = len;
        if( len <= _mem.length  ) return;

        // If memory is too small, grow it
        int newlen = _mem.length;
        while( len > newlen )
            newlen <<= 1;   // Next larger power-of-2
        _mem = Arrays.copyOf(_mem, newlen);
    }

    // Big-endian read long from any array, zero fill off end
    static long read8( byte[] src, int off ) {
        long x = 0;
        for( int i=0; i<8; i++ ) {
            x <<= 8;
            x |= (off+i < src.length ? (src[off + i] & 0xFF) : 0);
        }
        return x;
    }

    // Big-endian write int to any array, any offset
    static void write4( byte[] dst, int off, int x ) {
        for( int i=0; i<4; i++ ) {
            dst[off + 3-i] = (byte)x;
            x >>= 8;
        }
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

    void write1( int adr, long b ) {
        growMem( adr+1 );
        _mem[adr] = (byte)b;
    }

    // Array write.  Caller checks args are positive.
    // `soff+len` is allowed to be larger than `src.length`;
    // in which case the extra is zero-filled.
    void write( int doff, byte[] src, int soff, int len ) {
        growMem(doff+len);
        int copyLen = Math.min(len,src.length-soff);
        System.arraycopy(src,soff,_mem,doff,copyLen);
        if( copyLen < len ) Arrays.fill(_mem,doff+copyLen,doff+len,(byte)0);
    }

    void write( int doff, Bytes src, int soff, int len ) {
        if( len==0 ) return; // Nothing to write, and do not grow backing store
        growMem(doff+len);
        int slen = src.size();
        for( int i=0; i<Math.min(slen-soff,len); i++ )
            _mem[doff+i] = src.get(soff+i);
    }


    // Just wrap as a Bytes array
    Bytes asBytes(int off, int len) {
        if( len==0 ) return Bytes.EMPTY;
        return Bytes.wrap(_mem,off,len);
    }

    // Copy from src to dst for len, extending as needed.
    void copy( int dst, int src, int len ) {
        growMem(Math.max(src,dst)+len);
        System.arraycopy(_mem,src,_mem,dst,len);
    }


    Bytes copyBytes(int off, int len) {
        return Bytes.wrap(Arrays.copyOfRange(_mem,off,off+len),0,len);
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
