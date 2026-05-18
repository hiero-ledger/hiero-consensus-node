// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.google.common.base.MoreObjects;
import com.hedera.node.app.service.contract.impl.state.AbstractMutableEvmAccount;
import com.hedera.node.app.service.contract.impl.utils.TODO;
import com.hedera.pbj.runtime.io.WritableSequentialData;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.UncheckedIOException;
import java.nio.BufferOverflowException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;

// Bonneville Code object.  Immutable bare byte array.  Cached, hashed and
// interned.  Cheap hash is used because the prior caching spends all its time
// on a crypto-secure hash...  which is strictly not-needed for this perf hack.
// Caches the validation as well.  Implements OutputStream soley so it can get
// the raw backing byte array from a PBJ Bytes object.

// TODO: age-out old contracts from the intern table.  Can be as simple as
// removing a CodeV2 at random - hot ones will just re-install.

// spotless:off
public class CodeV2 extends Code implements WritableSequentialData {

    private static final ConcurrentHashMap<CodeV2, CodeV2> CODES = new ConcurrentHashMap<>();

    private static final ArrayList<CodeV2> FREE = new ArrayList<>();

    public static final CodeV2 EMPTY = make(new byte[0]);

    // EVM bytecodes. This array is commonly shared and must be immutable
    byte[] _codes;
    // Handles to avoid a copy
    int _off, _len;
    // "Good enough" non-secure fast Hashcode, computed once
    private int _hash;

    public CodeV2(Bytes byteCode, Hash codeHash) {
        super(byteCode, codeHash);
    }

    // Return an empty CodeV2, from the FREE list if possible
    private static CodeV2 atomicGetFree() {
        // Under lock, pull from the free list
        synchronized( FREE ) {
            if( !FREE.isEmpty() )
                return FREE.removeLast();
        }
        return new CodeV2(null, null);
    }

    // Put an unused CodeV2 back to the FREE list and return the winner
    private CodeV2 atomicPutFree( CodeV2 winner ) {
        // Mark it as obviously freed (_codes=null, _hash=0)
        _codes = null;
        _hash = 0;
        // Under lock, push onto the free list
        synchronized( FREE ) { FREE.add(this); }
        return winner;
    }

    // Attempt to intern: if we've seen this before, use the old one.
    // If not, and interning, atomic record as the next "old one".
    private CodeV2 atomicIntern(boolean intern) {
        assert _hash==0;
        // Set the hash.  Yea Olde String Hash for us.
        int hash = 0;
        for( int i = 0; i < _len; i++ )
            hash = hash*31 + _codes[i + _off];
        if( hash == 0 ) hash = 0xDEADBEEF; // Avoid the appearance of a not-set zero hash
        _hash = hash;

        CodeV2 old = CODES.get(this);
        if( old != null )
            return atomicPutFree(old); // Got a Winner!
        // No prior, so do expensive setup.
        setUp();
        // If backing memory has scoped lifetime and will eventually get
        // recycled, do not intern this CodeV2.
        if( !intern )  return this;
        // Attempt probe again with a full setup code object.  We expect this
        // mostly wins, unless a racing other thread inserts the same shaped
        // code object in the same putIfAbsent
        old = CODES.putIfAbsent(this, this);
        if( old == null )       // We win?
            return this;        // Expected winner: return fresh code
        // Unexpected lost race, return old winner and return this to the free list
        return atomicPutFree(old);
    }

    // When called from an internal BEVM Memory array, the backing data will
    // recycle so do not save this array when interning.
    public static CodeV2 make(byte[] codes, int off, int len, boolean intern) {
        CodeV2 code = atomicGetFree();

        // Fill in codes fields
        code._codes= codes;
        code._off  = off;
        code._len  = len;

        return code.atomicIntern(intern);
    }

    public static CodeV2 make(byte[] codes) {
        // Since exact size, assumed immutable and no copy will be made.
        return make(codes, 0, codes.length, true);
    }

    // Pull the code from an Account withOUT a copy by having CodeV2 mimic an
    // OutputStream, and PBJ Bytes will (without a copy) inject the raw bytes
    // for us.
    public static CodeV2 make(AbstractMutableEvmAccount act) {
        if( act == null ) return EMPTY;
        CodeV2 code = atomicGetFree();

        // Fill in codes fields - Look Ma!  No Copy!
        act.getCodePBJ().writeTo(code);

        return code.atomicIntern(true);
    }

    private BitSet _jmpDest; // Set if we are keeping "this"
    // Things to do after we interned the Code and before we try to use it.
    private void setUp() {
        // One-time fill jmpDest cache
        _jmpDest = new BitSet();
        for( int i = 0; i < _len; i++ ) {
            int op = _codes[i+_off] & 0xFF;
            if( op == 0x5B ) _jmpDest.set(i); // Set Jump Destination opcodes
            if( op >= 0x60 && op < 0x80 ) i += op - 0x60 + 1; // Skip immediate bytes
        }

        // TODO: Cache the expensive keccak256 cache
    }

    // Must jump to a jump dest, opcode 91/0x5B
    boolean jumpValid(int dst) {
        return dst >= 0 && dst < _len && _jmpDest.get(dst);
    }

    @Override
    public boolean equals(Object o) {
        if( !(o instanceof CodeV2 code) ) return false;
        if( _len != code._len ) return false;
        if( _codes == code._codes && _off == code._off ) return true;
        for( int i = 0; i < _len; i++ )
            if( _codes[i + _off] != code._codes[i + code._off] )
                return false;
        return true;
    }

    @Override public int hashCode() { return _hash; }

    @Override public String toString() {
        byte[] encoded = Base64.getEncoder().encode(_codes);
        return MoreObjects.toStringHelper(this).add("bytes", new String(encoded, _off, _len)).toString();
    }

    // --------------------------------------
    // Become a WritableSequentialData, so PBJ Bytes can hand us the byte[] directly.
    @Override
    public void writeBytes(@NonNull final byte[] src, final int offset, final int length)
            throws BufferOverflowException, UncheckedIOException {
        _codes = src;             // Direct byte[].  We promise not to modify!!!
        _off = offset;
        _len = length;
    }

    @Override public void writeByte(byte b) throws BufferOverflowException, UncheckedIOException { throw new TODO(); }
    @Override public long capacity() { throw new TODO(); }
    @Override public long position() { throw new TODO(); }
    @Override public long limit() { throw new TODO(); }
    @Override public void limit(long limit) { throw new TODO(); }
    @Override public void skip(long count) throws UncheckedIOException { throw new TODO(); }

    // --------------------------------------
    // Called at least by CustomContractCreationProcessor for CODE_SUCCESS with
    // side-car support.  This usage can probably be removed with a rewriting
    // of CCCP (which is already on my TODO-list)
    private Bytes _bytes;

    @Override public Bytes getBytes() {
        return _bytes == null ? (_bytes = Bytes.wrap(_codes,_off,_len)) : _bytes;
    }

    private Hash _kekhash;
    @Override public Hash getCodeHash() {
        return _kekhash == null ? (_kekhash = Hash.hash(getBytes())) : _kekhash;
    }

    @Override public int getSize() { return _len; }

    @Override public boolean isJumpDestInvalid(int dst) {
        // return !jumpValid(dst); // Obvious execution strat, but still hoping never called
        throw new TODO();
    }
    @Override public String prettyPrint() { throw new TODO(); }
    @Override public long[] getJumpDestBitMask() { throw new TODO(); }
    @Override public void setJumpDestBitMask(long[] jumpDestBitMask) { throw new TODO(); }
}
// spotless:on
