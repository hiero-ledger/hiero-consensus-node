// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.node.app.service.contract.impl.hevm.HederaEvmTransactionResult;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.utils.TODO;

import java.util.*;

// BESU imports
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;

/**
 *
 * Design notes:
 * /p
 * I have decoupled GasCalculator.mStoreOperationGasCost from using a
 * MessageFrame, not available here.  This means inlining the required Memory
 * into my local copy of {@code mStoreOperationGasCost} which is private to a
 * BonnevilleEVM so it can be changed without impacting anything else.
 */
public class BonnevilleEVM {
    // Receiver account
    final Account _recv;
    // Contract bytecodes
    final byte[] _codes;
    // How to calculate gas
    final GasCalculator _gasCalc;
    // Gas when we started
    final long _startGas;
    // Gas available, runs down to zero
    private long _gas;

    // Custom SLoad asks this global question
    final boolean _isSidecarEnabled;

    // Halt reason, or null
    private ExceptionalHaltReason _halt;

    // TODO: Recycle these on a free list; lifetime is hard-limited to a single
    // contract so should pool nicely.

    public BonnevilleEVM( Account recv, byte[] codes, GasCalculator gasCalc, long gas, boolean isSidecarEnabled ) {
        _recv = recv;
        _codes = codes;
        _gasCalc = gasCalc;
        _startGas = gas;
        _gas = gas;
        if( _gasCalc.getVeryLowTierGasCost() > 10 )
            throw new TODO("Need to restructure how gas is computed");
        _isSidecarEnabled = isSidecarEnabled;
    }

    public long gasUsed() { return Math.max(_startGas,_startGas - _gas); }

    public HederaEvmTransactionResult result() {
        throw new TODO();
    }

    public Set<Address> getSelfDestructs() {
        throw new TODO();
    }

    public StorageAccessTracker accessTracker() { return null; }

    public BonnevilleEVM run() {
        // TODO: setup
        _halt = _run();
        // TODO: cleanup
        throw new TODO();
    }


    private ExceptionalHaltReason useGas( long used ) {
        return (_gas -= used) < 0 ? ExceptionalHaltReason.INSUFFICIENT_GAS : null;
    }

    // -----------------------------------------------------------
    // The Stack Implementation
    private final int MAX_STACK_SIZE = 1024;

    private int _sp;            // The stack pointer

    // The stack is a struct-of-arrays.
    // There are no "stack value" objects.

    // 256b elements are 4 longs.

    private final long[] STK0 = new long[MAX_STACK_SIZE];
    private final long[] STK1 = new long[MAX_STACK_SIZE];
    private final long[] STK2 = new long[MAX_STACK_SIZE];
    private final long[] STK3 = new long[MAX_STACK_SIZE];

    // Push a 256b
    private ExceptionalHaltReason push( long x0, long x1, long x2, long x3 ) {
        if( _sp==MAX_STACK_SIZE )
            return ExceptionalHaltReason.TOO_MANY_STACK_ITEMS;
        STK0[_sp] = x0;
        STK1[_sp] = x1;
        STK2[_sp] = x2;
        STK3[_sp] = x3;
        _sp++;
        return null;
    }

    // Push a long
    private ExceptionalHaltReason push( long x ) { return push(x,0,0,0); }

    // Push a byte array
    private ExceptionalHaltReason push( byte[] src, int off, int len ) {
        // Caller range-checked already
        assert src != null && off >= 0 && len>=0 && off+len < src.length;
        long x0 = getLong(src,off, len);  len -= 8;
        long x1 = getLong(src,off, len);  len -= 8;
        long x2 = getLong(src,off, len);  len -= 8;
        long x3 = getLong(src,off, len);
        return push(x0,x1,x2,x3);
    }

    // Misaligned long load, which might be short.
    // TODO: Unsafe or ByteBuffer
    private long getLong( byte[] src, int off, int len ) {
        long adr = 0;
        if( len<=0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) <<  0;
        if( len==0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) <<  8;
        if( len==0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) << 16;
        if( len==0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) << 24;
        if( len==0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) << 32;
        if( len==0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) << 40;
        if( len==0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) << 48;
        if( len==0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF) << 56;
        return adr;
    }


    // Return a memory address as a unsigned int.  Larger values are clamped at
    // Integer.MAX_VALUE.  Result is in *WORDS*.
    private int popMemAddress() {
        assert _sp > 0;         // Caller already checked for stack underflow
        _sp--;
        if( STK1[_sp]!=0 || STK2[_sp]!=0 || STK3[_sp]!=0 )
            return Integer.MAX_VALUE;
        if( STK0[_sp] < 0 || STK0[_sp] > Integer.MAX_VALUE )
            return Integer.MAX_VALUE;
        return (int)(STK0[_sp]>>5); // Convert byte address to WORD
    }

    // All arguments are in *words*
    private ExceptionalHaltReason popStackWriteMem( int adr ) {
        assert _sp > 0;         // Caller already checked for underflow
        growMem( adr+1 );
        MEM0[adr] = STK0[_sp];
        MEM1[adr] = STK1[_sp];
        MEM2[adr] = STK2[_sp];
        MEM3[adr] = STK3[_sp--];
        return null;
    }


    // -----------------------------------------------------------
    // The Memory Implementation
    int _len;                   // Current active WORDS in-use
    private long[] MEM0 = new long[1];
    private long[] MEM1 = new long[1];
    private long[] MEM2 = new long[1];
    private long[] MEM3 = new long[1];

    // Grow to handle len Words
    private void growMem( int len ) {
        // Grow in-use words
        if( len < _len ) return;
        _len = len;
        if( _len < MEM0.length ) return;

        // If memory is too small, grow it
        int newlen = MEM0.length;
        while( len > newlen )
            newlen <<= 1;   // Next larger power-of-2
        MEM0 = Arrays.copyOf(MEM0, newlen);
        MEM1 = Arrays.copyOf(MEM1, newlen);
        MEM2 = Arrays.copyOf(MEM2, newlen);
        MEM3 = Arrays.copyOf(MEM3, newlen);
    }


    // -----------------------------------------------------------
    // Execute bytecodes until done
    ExceptionalHaltReason _run() {
        // TODO: setup, rethink exit condition
        int pc = 0;
        ExceptionalHaltReason halt = null;

        while( halt==null ) {
            int op = _codes[pc++] & 0xFF;
            halt = switch( op ) {

            case 0x0A -> exp();

            case 0x52 -> mstore();

            case 0x54 -> customSLoad(); // Hedera custom SLOAD
            case 0x5F -> push0();

            case 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,
                 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F
                 // push an array of immediate bytes onto stack
                 -> push(pc, pc += (op-0x60+1));

            case 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F
                 // Duplicate nth word
                 -> dup(op-0x80+1);

            case 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F
                 // Duplicate nth word
                 -> swap(op-0x90+1);

            default ->
                throw new TODO(String.format("Unhandled bytecode 0x%02X",_codes[pc-1]));
            };
        }
        return halt;
    }


    // ---------------------
    // Simple stack ops

    // Push an immediate 0 to stack
    private ExceptionalHaltReason push0() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push( 0L );
    }

    // Push an array of immediate bytes onto stack
    private ExceptionalHaltReason push( int pc, int newpc ) {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        return push( _codes, pc, newpc-pc );
    }

    // Duplicate nth word
    private ExceptionalHaltReason dup( int n ) {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < n ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long x0 = STK0[_sp-1-n];
        long x1 = STK1[_sp-1-n];
        long x2 = STK2[_sp-1-n];
        long x3 = STK3[_sp-1-n];
        return push(x0,x1,x2,x3);
    }

    // Swap nth word
    private ExceptionalHaltReason swap( int n ) {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp <= n ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long tmp0 = STK0[_sp-1-n]; STK0[_sp-1-n] = STK0[_sp-1]; STK0[_sp-1] = tmp0;
        long tmp1 = STK1[_sp-1-n]; STK1[_sp-1-n] = STK1[_sp-1]; STK1[_sp-1] = tmp1;
        long tmp2 = STK2[_sp-1-n]; STK2[_sp-1-n] = STK2[_sp-1]; STK2[_sp-1] = tmp2;
        long tmp3 = STK3[_sp-1-n]; STK3[_sp-1-n] = STK3[_sp-1]; STK3[_sp-1] = tmp3;
        return null;
    }

    // ---------------------
    // Memory ops

    // Memory Store
    private ExceptionalHaltReason mstore() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int adr = popMemAddress();
        if( adr == Integer.MAX_VALUE ) return useGas(adr); // Fail, out of gas

        // Memory store gas cost from {@link FrontierGasCalculator} that is
        // decoupled from {@link MemoryFrame}.
        long gas = _gasCalc.getVeryLowTierGasCost() + memoryExpansionGasCost(adr,1);
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        return popStackWriteMem(adr);
    }

    // All arguments are in *words*
    private long memoryExpansionGasCost(int adr, int nwords) {
        assert adr >=0 && nwords >= 0;                 // Caller already checked
        if( adr+nwords < 0 ) return Integer.MAX_VALUE; // Overflow gas cost
        if( adr+nwords < _len ) return 0;              // No extension, so no memory cost
        long pre  = memoryCost( _len );
        long post = memoryCost(adr+nwords);
        return post - pre;
    }

    // A version of {@link FrontierGasCalculator.memoryCost} from (used through
    // at least {@link CancunGasCalculator}) using a {@code int} for length.
    // Values larger than an int will fail for gas usage first.
    // Values int or smaller will never overflow a long, and so do not need
    // range checks or clamping.
    private long memoryCost(int nwords) {
        assert nwords >= 0;     // Caller checked
        long words2 = (long)nwords*nwords;
        long base = words2>>9;  // divide 512
        return base + nwords*_gasCalc.getVeryLowTierGasCost()/*FrontierGasCalculator.MEMORY_WORD_GAS_COST*/;
    }

    // ---------------------
    // Permanent Storage ops

    // Is address+key warmed up?  Used for gas calcs
    private final ArrayList<AdrKey> _freeAK = new ArrayList<>();
    private final HashMap<AdrKey,AdrKey> _internAK = new HashMap<>();
    private static class AdrKey {
        Address _adr;
        byte[] _keys = new byte[32];
        UInt256 _ui256; // Warm-up flag; also used to access Account.getStorageValue
        boolean isWarm() {
            if( _ui256!=null ) return true;
            _ui256 = UInt256.fromBytes(Bytes.wrap(_keys));
            return false;       // Was cold, but warmed-up afterwards
        }
        @Override public int hashCode() { return _adr.hashCode() ^ Arrays.hashCode(_keys); }
        @Override public boolean equals( Object o ) {
            if( !(o instanceof AdrKey ak) ) return false;
            return _adr.equals(ak._adr) && Arrays.equals(_keys,ak._keys);
        }
    }
    // Get and intern a slot based on Address and key
    AdrKey getSlot( Address adr, long key0, long key1, long key2, long key3) {
        AdrKey ak = _freeAK.isEmpty() ? new AdrKey() : _freeAK.removeLast();
        ak._adr = adr;

        //ak._keys[0] = (int)(key0>>32);
        //ak._keys[1] = (int)key0;
        //ak._keys[2] = (int)(key1>>32);
        //ak._keys[3] = (int)key1;
        //ak._keys[4] = (int)(key2>>32);
        //ak._keys[5] = (int)key2;
        //ak._keys[6] = (int)(key3>>32);
        //ak._keys[7] = (int)key3;
        //AdrKey ak2 = _internAK.get(ak);
        //if( ak2 != null )
        //    { _freeAK.add(ak); return ak2; }
        //_internAK.put(ak,ak);
        //return ak;
        throw new TODO();
    }



    // Load from the global/permanent store
    private ExceptionalHaltReason sLoad() {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long key0 = STK0[_sp];
        long key1 = STK1[_sp];
        long key2 = STK2[_sp];
        long key3 = STK3[_sp--];

        // Warmup address; true if already warm.  This is a per-transaction
        // tracking and is only for gas costs.  The actual warming happens if
        // we get past the gas test.
        AdrKey ak = getSlot(_recv.getAddress(), key0, key1, key2, key3);
        long gas = _gasCalc.getSloadOperationGasCost() +
            (ak.isWarm() ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdSloadCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        // UInt256 already in AdrKey
        UInt256 val = _recv.getStorageValue( ak._ui256 );
        //return push(val);
        throw new TODO();
    }

    private ExceptionalHaltReason customSLoad() {
        var halt = sLoad();
        if( halt==null && _isSidecarEnabled ) {
            throw new TODO();
        }
        throw new TODO();
    }


    // ---------------------
    // Arithmetic

    // Exponent
    private ExceptionalHaltReason exp() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long base0 = STK0[_sp];
        long base1 = STK1[_sp];
        long base2 = STK2[_sp];
        long base3 = STK3[_sp--];
        long pow0  = STK0[_sp];
        long pow1  = STK1[_sp];
        long pow2  = STK2[_sp];
        long pow3  = STK3[_sp];

        // Gas is based on busy longs in the power, converted to bytes
        int numBytes =
            pow3 != 0 ? 32 :
            pow2 != 0 ? 24 :
            pow1 != 0 ? 16 :
            pow0 != 0 ?  8 : 0;
        var halt = useGas(_gasCalc.expOperationGasCost(numBytes));
        if( halt!=null ) return halt;

        if( base1 == 0 && base2 == 0 && base3 == 0 ) {
            if( base0 == 0 ) {
                // 1^pow == 1
                STK0[_sp] = 1;
                STK1[_sp] = 0;
                STK2[_sp] = 0;
                STK3[_sp] = 0;
                return null;
            }
        }
        // Prolly BigInteger
        throw new TODO();
    }
}
