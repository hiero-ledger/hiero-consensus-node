// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.swirlds.config.api.Configuration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Supplier;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.evm.frame.MessageFrame;

// The top level execution of a series of (possibly) nested transactions,
// running Bonneville/BEVM instances as they nest down the stack.  The whole
// stack atomically executes or not.  This class executes single-threaded.
// spotless:off
public class TopXTN {

    // The One True Bonneville, shared across all threads
    public final BonnevilleEVM _bonneville;

    // Make a new Top-level transaction processor, one per thread
    TopXTN( BonnevilleEVM bonneville ) {
        _bonneville = bonneville;
    }

    // ---------------
    // Fields that change with each top-level XTN execution

    // Some context flags
    Address _hookOwner;
    Configuration _config;

    // An ASCTracer, changes with each execution.
    ActionSidecarContentTracer _tracer;
    // Tracking side-car data per-frame
    boolean _hasSideCar;
    // Tracking side-car state per-load/store
    boolean _hasStateSideCar;


    // ---------------
    // A collection of BEVM's, to be reused as contracts nest
    public final ArrayList<BEVM> _bevms = new ArrayList<>();
    private int _nbevms;        // Number of BEVMs in-use

    public void run( MessageFrame frame, ActionSidecarContentTracer tracer, CodeV2 code ) {
        // Properly reset between XTNs
        assert _n256s == 0;
        assert _adrkeys == 0;

        // Side-car tracking (despite the tracer name, it tracks sidecars)
        _tracer = tracer;

        // Pull out some common flags
        _config     = frame.getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE);
        _hookOwner  = frame.getContextVariable(FrameUtils.HOOK_OWNER_ADDRESS);
        _hasSideCar = frame.hasContextVariable(FrameUtils.ACTION_SIDECARS_VARIABLE);

        // Custom sidecar for state changes
        _hasStateSideCar = _config != null && _bonneville._flags.isSidecarEnabled(frame, SidecarType.CONTRACT_STATE_CHANGE);
        assert _hasSideCar || !_hasStateSideCar; // If tracking state changes, must track contract calls


        // Load up the top-level BEVM and run some opcodes
        runNestedBEVM( frame, code, null );

        // Pop the action stack for not-pre-compiled.
        if( _hasSideCar )
            tracer.traceNotExecuting(frame);

        // Reset at top level
        _adrkeys = 0;
        _n256s = 0;
    }

    // Run a nested BEVM.  This function is called recursively.
    void runNestedBEVM(MessageFrame frame, CodeV2 code, Address parentContract) {
        // Make a BEVM on-demand.  Cache them in this thread.
        if( _nbevms == _bevms.size() )
            _bevms.add(new BEVM(this));
        BEVM bevm = _bevms.get(_nbevms++);

        bevm.init(code, frame, parentContract).run(_nbevms==1).reset();

        _nbevms--;
    }


    // ---------------
    // Top-level shared UInt256 cache.  These are commonly used during
    // execution but commonly vary in different XTNs - as they commonly hold
    // hashes.  Interning them helps the later caches use a faster plain ==
    // check instead of equals().
    private short _n256s;
    private long[] _x0s=new long[1], _x1s=new long[1], _x2s=new long[1], _x3s=new long[1];
    private UInt256[] _u256s = new UInt256[1];

    long x0(int x) { return _x0s[x]; }
    long x1(int x) { return _x1s[x]; }
    long x2(int x) { return _x2s[x]; }
    long x3(int x) { return _x3s[x]; }

    UInt256 uint256(long x0, long x1, long x2, long x3) {
        // Check for a hit
        for( int i=0; i<_n256s; i++ )
            if( _x0s[i]==x0 && _x1s[i]==x1 && _x2s[i]==x2 && _x3s[i]==x3 )
                return _u256s[i];

        // Need to allocate a cache slot.  Grow if needed
        grow();

        // Make a UI256
        UInt256 u256;
        if( x1 == 0 && x2 == 0 && x3 == 0 && 0 <= x0 && x0 < 64 )
            u256 = UInt256.valueOf(x0);
        else {
            // Build a UInt256 the hard way
            byte[] bs = new byte[32];
            Memory.write8(bs, 24, x0);
            Memory.write8(bs, 16, x1);
            Memory.write8(bs,  8, x2);
            Memory.write8(bs,  0, x3);
            // Wildly inefficient UIn256 constructor path
            u256 = UInt256.fromBytes(Bytes.wrap(bs));
        }
        // Install in cache
        _x0s[_n256s] = x0;
        _x1s[_n256s] = x1;
        _x2s[_n256s] = x2;
        _x3s[_n256s] = x3;
        _u256s[_n256s++] = u256;
        return u256;
    }

    // Need to allocate a cache slot.  Grow if needed
    private void grow() {
        if( _n256s < _x0s.length ) return;
        assert _n256s < 32767;  // More than 32K of these in a top-level contract?  Revisit assumptions.
        _x0s   = Arrays.copyOf(  _x0s,_n256s<<1);
        _x1s   = Arrays.copyOf(  _x1s,_n256s<<1);
        _x2s   = Arrays.copyOf(  _x2s,_n256s<<1);
        _x3s   = Arrays.copyOf(  _x3s,_n256s<<1);
        _u256s = Arrays.copyOf(_u256s,_n256s<<1);
    }

    // Intern an existing UInt256, and return an index into cache
    short ui256x(UInt256 u) {
        // Check for the existing cache hitting the fast way
        for( int i=0; i<_n256s; i++ )
            if( _u256s[i]==u )
                return (short)i;
        // Check for the existing cache hitting the expensive way
        for( int i=0; i<_n256s; i++ )
            if( u.equals(_u256s[i]) )
                return (short)i;
        // Need to allocate a cache slot.  Grow if needed
        grow();

        // Install
        short x = _n256s;
        _x0s[_n256s] = getLong(u,3);
        _x1s[_n256s] = getLong(u,2);
        _x2s[_n256s] = getLong(u,1);
        _x3s[_n256s] = getLong(u,0);
        _u256s[_n256s++] = u;
        return x;
    }

    // Get 1 of 4 longs out of a UInt26.  Utility to support a long-striped
    // stack in Bonneville.
    private static long getLong(UInt256 u, int idx) {
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

    // ---------------
    // Storage "slots" are keyed by (Address,UInt256) and are cold until first
    // touched.  "cold" is reset at top-level contracts, and "warm" is passed
    // down to all child contract calls.  Reverted contracts undo their
    // "warming" touches as if they never happened.

    // Top-level single-threaded shared-by-BEVMs warm-address stack.
    int _adrkeys;               // Number of stack entries
    private Address[] _adrs = new Address[1];

    // Index into the above UInt256 key array OR -1 for the null or missing
    // UInt256.
    private short[] _keyxs = new short[1];

    boolean isWarm( Address adr ) { return isWarm(adr, null); }
    boolean isWarm( Address adr, UInt256 key ) {
        short keyx = key == null ? -1 : ui256x(key);
        for( int i=0; i<_adrkeys; i++ )
            if( _adrs[i].equals(adr) && _keyxs[i]==keyx )
                return true;    // Hit, must be warm
        // Need to allocate a warm slot.  Grow if needed
        if( _adrkeys == _adrs.length ) {
            _adrs = Arrays.copyOf(_adrs ,_adrkeys<<1);
            _keyxs= Arrays.copyOf(_keyxs,_adrkeys<<1);
        }
        // Add adr/key slot
        _adrs [_adrkeys  ] = adr;
        _keyxs[_adrkeys++] = keyx;
        return false;           // Was missing, cold.  Is warm now
    }
}
// spotless:on
