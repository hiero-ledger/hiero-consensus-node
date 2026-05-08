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

    public void run(MessageFrame frame, ActionSidecarContentTracer tracer, CodeV2 code ) {
        // Properly reset between XTNs
        assert _n256s==0;
        assert _warmAdrStk.isEmpty();

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
        runNestedBEVM(frame,code,null);

        // Pop the action stack for not-pre-compiled.
        if( _hasSideCar )
            tracer.traceNotExecuting(frame);

        // Reset at top level
        _warmAdrStk.clear();
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
    private int _n256s;
    private long[] _x0s=new long[1], _x1s=new long[1], _x2s=new long[1], _x3s=new long[1];
    private UInt256[] _u256s = new UInt256[1];

    UInt256 uint256(long x0, long x1, long x2, long x3) {
        // Small values are already interned
        if( x1 == 0 && x2 == 0 && x3 == 0 && 0 <= x0 && x0 < 64 )
            return UInt256.valueOf(x0);

        // Check for a hit
        for( int i=0; i<_n256s; i++ )
            if( _x0s[i]==x0 && _x1s[i]==x1 && _x2s[i]==x2 && _x3s[i]==x3 )
                return _u256s[i];
        if( _n256s == _x0s.length ) {
            _x0s   = Arrays.copyOf(  _x0s,_n256s<<1);
            _x1s   = Arrays.copyOf(  _x1s,_n256s<<1);
            _x2s   = Arrays.copyOf(  _x2s,_n256s<<1);
            _x3s   = Arrays.copyOf(  _x3s,_n256s<<1);
            _u256s = Arrays.copyOf(_u256s,_n256s<<1);
        }

        // Build a UInt256
        byte[] bs = new byte[32];
        Memory.write8(bs, 24, x0);
        Memory.write8(bs, 16, x1);
        Memory.write8(bs,  8, x2);
        Memory.write8(bs,  0, x3);
        // Wildly inefficent UIn256 constructor path
        UInt256 u256 = UInt256.fromBytes(Bytes.wrap(bs));
        // Install in cache
        _x0s[_n256s] = x0;
        _x1s[_n256s] = x1;
        _x2s[_n256s] = x2;
        _x3s[_n256s] = x3;
        _u256s[_n256s++] = u256;
        return u256;
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

    // ---------------
    // Top-level shared warm-address stack.  This is a stack of warmed-up
    // addresses used in gas cost accounting.  It gets popped if a contract
    // reverts, un-warming addresses the reverted contract warmed.
    final ArrayList<AdrKey> _warmAdrStk = new ArrayList<>();

}
