// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.node.app.service.contract.impl.utils.TODO;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Hash;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EVM;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.code.CodeSection;

// Bonneville Code.  Immutable bare byte array.  Cached, hashed and interned.
// Cheap hash is used because the prior caching spends all its time on a
// crypto-secure hash...  which is strictly not-needed for this perf hack.
// Caches the validation as well.
public class CodeV2 implements Code {

    static private final ConcurrentHashMap<CodeV2,CodeV2> CODES = new ConcurrentHashMap<>();

    static private final ArrayList<CodeV2> FREE = new ArrayList<>();

    static public final CodeV2 EMPTY = make(new byte[0]);

    static int PROBES,HITS;

    // EVM bytecodes
    byte[] _codes;
    int _off, _len;
    // "Good enough" non-secure fast Hashcode, computed once
    private int _hash;

    // Return a CodeV2 of these bytes
    static public CodeV2 make(byte[] codes, int off, int len) {
        CodeV2 code;
        synchronized( FREE ) {
            // Under lock, pull from free-list, do the cheap-init
            code = (FREE.isEmpty() ? new CodeV2() : FREE.removeLast()).init(codes,off,len);
            // probe the hash table
            PROBES++;
            CodeV2 old = CODES.get(code);
            if( old!=null ) {
                // Got a prior, so under lock, push code back on free list
                HITS++;
                return code.reset(old);
            }
        }
        // No prior, so do expensive setup.
        code.setUp();
        // Attempt again with a full setup code object.  We expect this mostly
        // wins, unless a racing other thread inserts the same shaped code
        // object in the same putIfAbsent
        assert code._len == code._codes.length;
        CodeV2 old = CODES.putIfAbsent(code,code);
        if( old==null )         // We win?
            return code;        // Expected winner: return fresh code
        // Unexpected lost race, return old and return fresh code
        // to free list
        return code.reset(old);
    }

    static public CodeV2 make(byte[] codes) {
        // Since exact size, assumed immutable and no copy will be made.
        return make(codes,0,codes.length);
    }

    static public CodeV2 make(Account act) {
        if( act==null ) return EMPTY;
        // Note that I cannot tell if this call copies, or not - or if the
        // backing store is immutable or not.  This means I'll end up making
        // Yet Another Defensive Copy because the underlying Codes API is
        // busted.

        // TODO: The call to `account.getCode()` makes a copy 100% of the time
        // during the HeliSwap benchmark and does not cache itself.
        return make(act.getCode().toArrayUnsafe());
    }

    private CodeV2 init( byte[] codes, int off, int len ) {
        assert _codes == null;
        assert off >= 0 && off+len <= codes.length;
        // No defensive copy made (yet)
        _codes = codes;
        _off = off;
        _len = len;
        int hash = 0;
        for( int i=0; i<len; i++ )
            hash += (codes[i+off]<<5)-codes[i+off];
        if( hash==0 ) hash = 0xDEADBEEF; // Avoid the appearance of a not-set zero hash
        _hash = hash;
        return this;
    }

    // We make a code object, and raced trying to install it and lost the race.
    // Mark it as obviously freed (_codes=null, _hash=0) and append to FREE
    // list and return the winner.
    private synchronized CodeV2 reset(CodeV2 winner) {
        _codes = null;
        _hash = 0;
        FREE.add(this);         // Add under lock
        return winner;
    }

    private BitSet _jmpDest;    // Set if we are keeping "this"
    // Things to do after we interned the Code and before we try to use it.
    private void setUp() {
        // Tracking of when the incoming data array is read-only - or not -
        // does not appear to be a thing.  So we'll *assume* (until crashed
        // otherwise) that it is immutable.

        // We specifically do *not* make this copy earlier, when probing the
        // cache, exactly to avoid the expense of the copy.
        if( _off!=0 || _len != _codes.length )
            _codes = Arrays.copyOfRange(_codes,_off,_off+_len);
        _off = 0;

        // One-time fill jmpDest cache
        _jmpDest = new BitSet();
        for( int i=0; i<_codes.length; i++ ) {
            int op = _codes[i] & 0xFF;
            if( op == 0x5B ) _jmpDest.set(i); // Set Jump Destination opcodes
            if( op >= 0x60 && op < 0x80 )
                i += op - 0x60 + 1; // Skip immediate bytes
        }

        // TODO: Cache the expensive kekkac256 cache
    }


    // Must jump to a jump dest, opcode 91/0x5B
    boolean jumpValid( int dst ) {
        return dst >= 0 && dst < _codes.length && _jmpDest.get( dst );
    }

    @Override public boolean equals(Object o) {
        if( !(o instanceof CodeV2 code) ) return false;
        if( _len!=code._len ) return false;
        if( _codes==code._codes && _off==code._off )
            return true;
        for( int i=0; i<_len; i++ )
            if( _codes[i+_off] != code._codes[i+code._off] )
                return false;
        return true;
    }

    @Override public int hashCode() { return _hash; }


    // --------------------------------------

    // CodeV2 objects not used if they are not also valid (check is made upon
    // construction).  However, I am trying to avoid implementing all things
    // about Code, and the MessageFrame.Builder constructor calls isValid, and
    // if valid ALSO calls getCodeSection.  If not valid, it sets a PC of 0.
    // Since the PC (and Code) objects are ignored here, the path of least
    // resistance is to return False for a perfectly valid CodeV2.
    @Override public boolean isValid() {
        return false;
    }

    // Called at least by CustomContractCreationProcessor for CODE_SUCCESS with
    // side-car support.  This usage can probably be removed with a rewriting
    // of CCCP (which is already on my TODO-list)
    private Bytes _bytes;
    @Override public Bytes getBytes() {
        return _bytes==null ? (_bytes= Bytes.wrap(_codes)) : _bytes;
    }

    private Hash _kekhash;
    @Override public Hash getCodeHash() {
        return _kekhash==null ? (_kekhash = Hash.hash(getBytes())) : _kekhash;
    }

    // --------------------------------------
    @Override public int getSize() { return _len; }
    @Override public int getDataSize() { throw new TODO(); }
    @Override public int getDeclaredDataSize() { throw new TODO(); }
    @Override public boolean isJumpDestInvalid(int dst) {
        //return !jumpValid(dst); // Obvious execution strat, but still hoping never called
        throw new TODO();
    }
    @Override public CodeSection getCodeSection(int section) { throw new TODO(); }
    @Override public int getCodeSectionCount() { throw new TODO(); }
    @Override public int getEofVersion() { throw new TODO(); }
    @Override public int getSubcontainerCount() { throw new TODO(); }
    @Override public Optional<Code> getSubContainer(int index, Bytes auxData, EVM evm) { throw new TODO(); }
    @Override public Bytes getData(int offset, int length) { throw new TODO(); }
    @Override public int readBigEndianI16(int startIndex) { throw new TODO(); }
    @Override public int readBigEndianU16(int startIndex) { throw new TODO(); }
    @Override public int readU8(int startIndex) { throw new TODO(); }
    @Override public String prettyPrint() { throw new TODO(); }
}
