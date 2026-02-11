// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.failure.CustomExceptionalHaltReason;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomExtCodeSizeOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation;
import com.hedera.node.app.service.contract.impl.exec.processors.*;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import com.hedera.node.app.service.contract.impl.hevm.HevmPropagatedCallFailure;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.contract.impl.utils.TODO;
import com.swirlds.config.api.Configuration;

import com.google.common.collect.ImmutableList;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.util.*;

// BESU imports
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.blockhash.BlockHashLookup;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.BlockValues;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.gascalculator.FrontierGasCalculator;
import org.hyperledger.besu.evm.gascalculator.TangerineWhistleGasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.operation.AbstractOperation;
import org.hyperledger.besu.evm.operation.ChainIdOperation;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.tracing.OperationTracer;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

public class BonnevilleEVM extends HEVM {
    public static final String TOP_SEP = "======================================================================";
    @NonNull final FeatureFlags _flags;

    public BonnevilleEVM(
            @NonNull OperationRegistry operations,
            @NonNull GasCalculator gasCalc,
            @NonNull EvmConfiguration evmConfiguration,
            @NonNull EvmSpecVersion evmSpecVersion,
            @NonNull FeatureFlags featureFlags) {
        super(operations, gasCalc, evmConfiguration, evmSpecVersion );
        _flags = featureFlags;
    }


    // Shared free-list of BEVMs, to avoid making new with every contract.
    private static final ArrayList<BEVM> FREE = new ArrayList<>();

    @Override
    public void runToHalt(MessageFrame frame, OperationTracer tracing) {
        runToHalt(null,frame,tracing);
    }

    void runToHalt(BEVM parent, MessageFrame frame, OperationTracer tracing ) {
        BEVM bevm;
        synchronized(FREE) {
            bevm = FREE.isEmpty()
                ? new BEVM(this,getOperationsUnsafe())
                : FREE.removeLast();
        }
        // Run the contract bytecodes
        bevm.init(parent,frame,tracing).run(parent==null).reset(parent==null);
        synchronized(FREE) { FREE.add(bevm); }
    }

    // ---------------------
    public static String OPNAME(int op) {
        if( op <  0x60 ) return OPNAMES[op];
        if( op <  0x80 ) return "psh"+(op-0x60+1);
        if( op <  0x90 ) return "dup"+(op-0x80+1);
        if( op <  0xA0 ) return "swp"+(op-0x90+1);
        if( op == 0xA0 ) return "log0";
        if( op == 0xA1 ) return "log1";
        if( op == 0xA2 ) return "log2";
        if( op == 0xA3 ) return "log3";
        if( op == 0xA4 ) return "log4";
        if( op == 0xF0 ) return "Crat";
        if( op == 0xF1 ) return "Call";
        if( op == 0xF2 ) return "CallCode";
        if( op == 0xF3 ) return "ret ";
        if( op == 0xF4 ) return "dCal";
        if( op == 0xF5 ) return "Crt2";
        if( op == 0xFA ) return "sCal";
        if( op == 0xFD ) return "revt";
        if( op == 0xFE ) return "invalid ";
        if( op == 0xFF ) return "self-destruct ";
        return String.format("%x",op);
    }
    private static final String[] OPNAMES = new String[]{
        /* 00 */ "stop", "add ", "mul ", "sub ", "div ", "sdiv", "mod ", "smod", "amod", "mmod", "exp ", "sign", "0C  ", "0D  ", "0E  ", "0F  ",
        /* 10 */ "ult ", "ugt ", "slt ", "sgt ", "eq  ", "eq0 ", "and ", "or  ", "xor ", "not ", "byte", "shl ", "shr ", "sar ", "1E  ", "1F  ",
        /* 20 */ "kecc", "21  ", "22  ", "23  ", "24  ", "25  ", "26  ", "27  ", "28  ", "29  ", "2A  ", "2B  ", "2C  ", "2D  ", "2E  ", "2F  ",
        /* 30 */ "addr", "bala", "orig", "calr", "cVal", "Load", "Size", "Data", "cdSz", "Copy", "gasP", "xSiz", "xCop", "retZ", "retC", "hash",
        /* 40 */ "blkH", "Coin", "time", "numb", "seed", "limi", "chid", "sbal", "fee ", "msiz", "blbH", "blob", "4C  ", "4D  ", "4E  ", "4F  ",
        /* 50 */ "pop ", "mld ", "mst ", "mst8", "Csld", "Csst", "jmp ", "jmpi", "pc  ", "59  ", "gas ", "noop", "tLd ", "tSt ", "mcpy", "psh0",
        };

    // Used in create()
    static final Bytes CREATE2_PREFIX = Bytes.fromHexString("0xFF");
    // Used in exp()
    static final BigInteger MOD_BASE = BigInteger.TWO.pow(256);

}

/**
 *
 * Design notes:
 * /p
 * I have decoupled GasCalculator.mStoreOperationGasCost from using a
 * MessageFrame, not available here.  This means inlining the required Memory
 * into my local copy of {@code mStoreOperationGasCost} which is private to a
 * BonnevilleEVM so it can be changed without impacting anything else.
 */
class BEVM {
    BonnevilleEVM _bevm;

    final GasCalculator _gasCalc;
    final boolean _gasTangerineWhistle; // True if TW, False if Frontier, ERROR is anything else

    final FeatureFlags _flags;
    final AddressChecks _adrChk;

    // Custom Self-Destruct
    final AbstractOperation _selfDestruct;

    // ChainID from BESU, not *CustomChainID*
    final long _chainID;


    // per-contract temp storage
    final Memory _mem;

    // Wrap a UInt256 in a Supplier for short-term usage, without allocating
    // per-bytecode.
    final UI256.Wrap _wrap0 = new UI256.Wrap(), _wrap1 = new UI256.Wrap();


    // Per-invocation fields

    OperationTracer _tracing;

    MessageFrame _frame;

    // Contract bytecodes
    byte[] _codes;
    // Per-code valid targets
    private BitSet _jmpDest;

    // Gas available, runs down to zero
    private long _gas;

    // Recipient
    @NonNull Account _recv;     // Always have a readable aocount
    MutableAccount _recvMutable;// Sometimes a writable account

    Configuration _config;

    // Custom SLoad asks this global question
    boolean _isSidecarEnabled;
    // Custom SLoad optional tracking
    StorageAccessTracker _tracker;

    // Top-level shared warm-address stack.
    final ArrayList<AdrKey> _warmAdrStkTopLevel = new ArrayList<AdrKey>();
    // Nested warm-address stack, points to some top-level stack
    ArrayList<AdrKey> _warmAdrStk;
    // Stack depth on entry, used to unwind on Revert
    int _warmAdrStkPtr;

    // Last Storage key, value.  Used to inform the Frame that storage was updated.
    UInt256 _lastSKey, _lastSVal;

    // Input data
    byte[] _callData;

    //
    ContractID _contractId;

    // Contract originator?
    Address _originator;


    BEVM( BonnevilleEVM bevm, @NotNull Operation[] operations ) {
        _bevm = bevm;

        _flags = bevm._flags;   // Feature Flags

        _gasCalc = bevm.getGasCalculator();
        // If SSTore minimum gas is ever not-zero, will need to check in sStore
        if( _gasCalc.getVeryLowTierGasCost() > 10 )
            throw new TODO("Need to restructure how gas is computed");
        // TW gas calc has a back-door call cost computation
        _gasTangerineWhistle = _gasCalc instanceof TangerineWhistleGasCalculator;
        assert _gasTangerineWhistle || (_gasCalc instanceof FrontierGasCalculator);

        // Local temp storage
        _mem = new Memory();

        // Address Check
        _adrChk = operations[0x3B] instanceof CustomExtCodeSizeOperation cecso ? cecso.addressChecks : null;
        //if( cExt.enableEIP3540 )
        //    throw new TODO();   // Assumed never in customExtCodeSize

        // Custom Self-Destruct hook
        _selfDestruct = (AbstractOperation)operations[0xFF];
        // ChainID from BESU, if no custom op overrides
        _chainID = operations[0x46] instanceof ChainIdOperation chain ? chain.getChainId().getLong(0) : 0;
    }


    BEVM init( BEVM parent, MessageFrame frame, OperationTracer tracing ) {

        _frame = frame;

        _tracing = tracing;

        // Bytecodes
        _codes = frame.getCode().getBytes().toArrayUnsafe();
        if( frame.getCode().getEofVersion() != 0 )
            throw new TODO("Expect eofVersion==0");
        assert _jmpDest == null;
        assert _sp == 0;

        // Starting and current gas
        _gas = frame.getRemainingGas();

        // Account receiver.  Can be null for various broken calls
        WorldUpdater updater = frame.getWorldUpdater();
        _recv        = updater.get       (frame.getRecipientAddress());
        _recvMutable = updater.getAccount(frame.getRecipientAddress());

        assert _mem._len == 0;

        _config = FrameUtils.initialFrameOf(_frame).getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE);

        // Hedera custom sidecar
        _isSidecarEnabled = _config!=null && _flags.isSidecarEnabled(frame, SidecarType.CONTRACT_STATE_CHANGE);
        // Hedera optional tracking first SLOAD
        _tracker = FrameUtils.accessTrackerFor(frame);

        // Preload input data
        _callData = _frame.getInputData().toArrayUnsafe();

        // TODO: Could get lazier here
        _contractId = updater instanceof ProxyWorldUpdater proxy
            ? proxy.getHederaContractIdNotThrowing(_frame.getRecipientAddress())
            : null;

        _originator = frame.getOriginatorAddress();

        // If top-level, use the top-level warm-address stack.
        // If NOT top-level, pass down the top-level stack.
        if( parent==null ) {
            assert allColdSlots();
            assert _warmAdrStkTopLevel.isEmpty();
            _warmAdrStk = _warmAdrStkTopLevel;
            _warmAdrStkPtr = 0;
        } else {
            _warmAdrStk = parent._warmAdrStk;
            _warmAdrStkPtr = _warmAdrStk.size();
            isWarm(parent._frame.getContractAddress());
        }
        isWarm(_frame.getSenderAddress());
        isWarm(_frame.getContractAddress());

        assert _lastSKey==null && _lastSVal == null;

        return this;
    }

    void reset(boolean topLevel) {
        if( topLevel ) {
            for( AdrKey ak : _warmAdrStkTopLevel )
                ak._warm = false;
            _warmAdrStkTopLevel.clear();
        } else {
            _warmAdrStk = null;
        }
        _mem.reset();
        _frame = null;
        _codes = null;
        _sp = 0;
        _callData = null;
        _contractId = null;
        _recv = null;
        _recvMutable = null;
        _isSidecarEnabled = false;
        _lastSKey = _lastSVal = null;
        _jmpDest = null;
    }

    private ExceptionalHaltReason useGas( long used ) {
        return (_gas -= used) < 0 ? ExceptionalHaltReason.INSUFFICIENT_GAS : null;
    }

    // -----------------------------------------------------------
    // The Stack Implementation
    public final int MAX_STACK_SIZE = 1024;

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
    // Push an immediate 0 to stack
    private ExceptionalHaltReason push0() { return push( 0L ); }


    // Push a UInt256
    private ExceptionalHaltReason push( UInt256 val ) {
        return push(UI256.getLong(val,3),
                    UI256.getLong(val,2),
                    UI256.getLong(val,1),
                    UI256.getLong(val,0));
    }


    // Address.delegate
    // -- delegate is ArrayWrappingBytes
    // -- -- wrapped bytes has offset 0, len== 20 == bytes[].length
    // 4 calls to getLong() accumulating bytes
    //
    private ExceptionalHaltReason push( Address adr ) {
        if( adr == null ) return push0();
        byte[] bs = adr.toArrayUnsafe();
        assert bs.length==20;
        long val0 = 0;
        for( int i=0; i<8; i++ )
            val0 = (val0 << 8) | (bs[12+i]&0xFF);
        long val1 = 0;
        for( int i=0; i<8; i++ )
            val1 = (val1 << 8) | (bs[ 4+i]&0xFF);
        long val2 = 0;
        for( int i=0; i<4; i++ )
            val2 = (val2 << 8) | (bs[   i]&0xFF);
        return push(val0,val1,val2,0);
    }

    // Push a byte array little-endian; short arrays are zero-filled high.
    private ExceptionalHaltReason push( byte[] src, int off, int len ) {
        // Caller range-checked already
        assert src != null && off >= 0 && len>=0 && off+len <= src.length;
        long x0 = getLong(src,off, len);  len -= 8;
        long x1 = getLong(src,off, len);  len -= 8;
        long x2 = getLong(src,off, len);  len -= 8;
        long x3 = getLong(src,off, len);
        return push(x0,x1,x2,x3);
    }

    private ExceptionalHaltReason push( BigInteger bi ) {
        var bytes = bi.toByteArray();
        var len = Math.min(32,bytes.length);
        return push(bytes,bytes.length-len,len);
    }



    // TODO, common case, optimize
    private ExceptionalHaltReason push32( byte[] src ) {
        assert src.length==32;
        return push(src,0,32);
    }

    // Misaligned long load, which might be short.
    // TODO: Unsafe or ByteBuffer
    private static long getLong( byte[] src, int off, int len ) {
        long adr = 0;
        if( len<=0 ) return adr;
        adr |= (long) (src[--len+off] & 0xFF);
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

    // Pop an unsigned long.  Larger values are clamped at Long.MAX_VALUE.
    private long popLong() {
        assert _sp > 0;         // Caller already checked for stack underflow
        return STK0[--_sp] < 0 || STK1[_sp]!=0 || STK2[_sp]!=0 || STK3[_sp]!=0
            ? Long.MAX_VALUE
            : STK0[_sp];
    }

    // Pop an unsigned int.  Larger values are clamped at Integer.MAX_VALUE.
    private int popInt() {
        assert _sp > 0;         // Caller already checked for stack underflow
        return STK0[--_sp] < 0 || STK1[_sp]!=0 || STK2[_sp]!=0 || STK3[_sp]!=0 || STK0[_sp] > Integer.MAX_VALUE
            ? Integer.MAX_VALUE
            : (int)STK0[_sp];
    }

    // Expensive
    private Bytes popBytes() {
        assert _sp > 0;         // Caller already checked for stack underflow
        long x0 = STK0[--_sp], x1 = STK1[_sp], x2 = STK2[_sp], x3 = STK3[_sp];
        byte[] bs = new byte[32];
        Memory.write8(bs,24,x0);
        Memory.write8(bs,16,x1);
        Memory.write8(bs, 8,x2);
        Memory.write8(bs, 0,x3);
        return Bytes.wrap(bs);
    }

    // Expensive
    private Address popAddress() {
        assert _sp > 0;         // Caller already checked for stack underflow
        long x0 = STK0[--_sp], x1 = STK1[_sp], x2 = STK2[_sp], x3 = STK3[_sp];
        byte[] bs = new byte[20];
        Memory.write8(bs,12,     x0);
        Memory.write8(bs, 4,     x1);
        Memory.write4(bs, 0,(int)x2);
        return Address.wrap(Bytes.wrap(bs));
    }

    private UInt256 popUInt256() {
        assert _sp > 0;         // Caller already checked for stack underflow
        long x0 = STK0[--_sp], x1 = STK1[_sp], x2 = STK2[_sp], x3 = STK3[_sp];
        return UI256.intern(x0,x1,x2,x3);
    }

    // -----------------------------------------------------------
    // Execute bytecodes until done
    BEVM run(boolean topLevel) {
        SB trace = new SB();
        PrintStream oldSysOut = System.out;
        if( trace != null ) {
            System.setOut(new PrintStream(new FileOutputStream( FileDescriptor.out)));
            if( topLevel ) System.out.println(BonnevilleEVM.TOP_SEP);
        }

        int pc = 0;
        ExceptionalHaltReason halt = null;

        while( halt==null ) {
            int op = pc < _codes.length ? _codes[pc] & 0xFF : 0;
            preTrace(trace,pc,op);
            pc++;

            halt = switch( op ) {

            case 0x00 -> stop();

                // Arithmetic ops
            case 0x01 -> add();
            case 0x02 -> mul();
            case 0x03 -> sub();
            case 0x04 -> div();
            case 0x05 -> sdiv();
            case 0x06 -> mod();
            case 0x07 -> smod();
            case 0x08 -> addmod();
            case 0x09 -> mulmod();
            case 0x0A -> exp();
            case 0x0B -> sign();
            case 0x0C, 0x0D, 0x0E, 0x0F -> ExceptionalHaltReason.INVALID_OPERATION;
            case 0x10 -> ult();
            case 0x11 -> ugt();
            case 0x12 -> slt();
            case 0x13 -> sgt();
            case 0x14 -> eq ();
            case 0x15 -> eqz();
            case 0x16 -> and();
            case 0x17 -> or ();
            case 0x18 -> xor();
            case 0x19 -> not();
            case 0x1A -> xbyte();
            case 0x1B -> shl();
            case 0x1C -> shr();
            case 0x1D -> sar();
            case 0x1E, 0x1F -> ExceptionalHaltReason.INVALID_OPERATION;

            case 0x20 -> keccak256();
            case       0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27 -> ExceptionalHaltReason.INVALID_OPERATION;
            case 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F -> ExceptionalHaltReason.INVALID_OPERATION;

            // call/input/output arguments
            case 0x30 -> address();
            case 0x31 -> balance();
            case 0x32 -> origin();
            case 0x33 -> caller();
            case 0x34 -> callValue();
            case 0x35 -> callDataLoad();
            case 0x36 -> callDataSize();
            case 0x37 -> callDataCopy();
            case 0x38 -> codeSize();
            case 0x39 -> codeCopy();
            case 0x3A -> gasPrice();
            case 0x3B -> customExtCodeSize();
            case 0x3C -> customExtCodeCopy();
            case 0x3D -> returnDataSize();
            case 0x3E -> returnDataCopy();
            case 0x3F -> customExtCodeHash();

            case 0x40 -> blockHash();
            case 0x41 -> coinBase();
            case 0x42 -> timeStamp();
            case 0x43 -> number();
            case 0x44 -> PRNGSeed();
            case 0x45 -> gasLimit();
            case 0x46 -> customChainId();
            case 0x47 -> selfBalance();
            case 0x48 -> baseFee();
            case 0x49 -> blobHash();
            case 0x4A -> blobBaseFee();
            case 0x4B, 0x4C, 0x4D, 0x4E, 0x4F -> ExceptionalHaltReason.INVALID_OPERATION;

            case 0x50 -> pop();

            // Memory, Storage
            case 0x51 -> mload();
            case 0x52 -> mstore();
            case 0x53 -> mstore8();
            case 0x54 -> customSLoad (); // Hedera custom SLOAD
            case 0x55 -> customSStore(); // Hedera custom STORE

            // The jumps
            case 0x56 ->        // Jump, target on stack
            ((pc=jump()   ) == -1) ? ExceptionalHaltReason.INVALID_JUMP_DESTINATION :
            ( pc            == -2) ? ExceptionalHaltReason.INSUFFICIENT_GAS :
            ( pc            == -3) ? ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS :
            null;               // No error, pc set correctly

            case 0x57 ->        // Conditional jump, target on stack
            ((pc=jumpi(pc)) == -1) ? ExceptionalHaltReason.INVALID_JUMP_DESTINATION :
            ( pc            == -2) ? ExceptionalHaltReason.INSUFFICIENT_GAS :
            ( pc            == -3) ? ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS :
            null;               // No error, pc set correctly

            case 0x58 -> pc(pc-1);
            case 0x59 -> msize();
            case 0x5A -> gas();
            case 0x5B -> noop();// Jump Destination, a no-op
            case 0x5C -> tLoad();
            case 0x5D -> tStore();
            case 0x5E -> mCopy();

            // Stack manipulation
            case 0x5F -> push0Op();

            case 0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,
                 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F
                 // push an array of immediate bytes onto stack
                 -> push(pc, pc += (op-0x60+1));

            case 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F
                 // Duplicate nth word
                 -> dup(op-0x80+1);

            case 0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E, 0x9F
                 // Swap nth word
                 -> swap(op-0x90+1);

            case 0xA0, 0xA1, 0xA2, 0xA3, 0xA4 -> customLog(op-0xA0);

            case 0xF0 -> create            (trace,"CREATE"  , false);
            case 0xF1 -> customCall        (trace,"CUSTCALL");
            case 0xF2 -> callCode          (trace,"CALL"    );
            case 0xF3 -> ret();
            case 0xF4 -> customDelegateCall(trace,"DELEGATE");
            case 0xF5 -> create            (trace,"CREATE2" , true );
            case 0xFA -> customStaticCall  (trace,"STATIC"  );
            case 0xFD -> revert();
            case 0xFF -> customSelfDestruct();

            default ->   ExceptionalHaltReason.INVALID_OPERATION;
            };

            if( trace != null ) {
                if( !(op >= 0xF0 && op <= 0xFA && op != 0xF3) ) {
                    postTrace(trace);
                    if( halt!=null && halt != ExceptionalHaltReason.NONE )
                        trace.p(" ").p(halt.toString());
                    System.out.println(trace);
                }
                trace.clear();
            }
        }

        if( trace != null ) {
            System.out.println();
            if( topLevel ) System.out.println(BonnevilleEVM.TOP_SEP);
            System.setOut(oldSysOut);
        }

        // Set state into Frame
        _frame.setPC(pc);
        _frame.setGasRemaining(_gas);
        if( _lastSKey != null )
            _frame.storageWasUpdated(_lastSKey, _lastSVal );
        if( halt != ExceptionalHaltReason.NONE ) {
            _frame.setExceptionalHaltReason(Optional.of(halt));
            _frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
        }
        return this;
    }

    private void preTrace(SB trace, int pc, int op) {
        if( trace !=null )
            trace.p("0x").hex2(pc).p(" ").p(BonnevilleEVM.OPNAME(op)).p(" ").hex4((int)_gas).p(" ").hex2(_sp).p(" -> ");
    }

    private SB postTrace(SB trace) {
        trace.hex2(_sp);
        // Dump TOS
        if( _sp > 0 ) trace.p(" 0x").hex8(STK3[_sp-1]).hex8(STK2[_sp-1]).hex8(STK1[_sp-1]).hex8(STK0[_sp-1]);
        return trace;
    }

    private void preTraceCall( SB trace, String str) {
        if( trace == null ) return;
        System.out.println(postTrace(trace).nl().nl().p("CONTRACT ").p(str).nl());
        trace.clear();
    }

    // ---------------------
    // Arithmetic

    // Add
    private ExceptionalHaltReason add() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        return add(lhs0,lhs1,lhs2,lhs3, rhs0,rhs1,rhs2,rhs3);
    }
    private ExceptionalHaltReason add(long lhs0, long lhs1, long lhs2, long lhs3,
                                      long rhs0, long rhs1, long rhs2, long rhs3) {
        // If both sign bits are the same and differ from the result, we overflowed
        long add0 = lhs0 + rhs0;
        long add1 = lhs1 + rhs1;
        if( overflowAdd(lhs0,rhs0,add0) ) add1++;
        long add2 = lhs2 + rhs2;
        if( overflowAdd(lhs1,rhs1,add1) ) add2++;
        long add3 = lhs3 + rhs3;
        if( overflowAdd(lhs2,rhs2,add2) ) add3++;
        // Math is mod 256, so ignore last overflow
        return push(add0,add1,add2,add3);
    }
    // Check the relationship amongst the sign bits only; the lower 63 bits are
    // computed but ignored.
    private static boolean overflowAdd( long x, long y, long sum ) { return ((x&y) | ((x^y) & ~sum)) < 0; }

    private ExceptionalHaltReason mul() {
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        return mul(lhs0,lhs1,lhs2,lhs3, rhs0,rhs1,rhs2,rhs3);
    }
    private ExceptionalHaltReason mul(long lhs0, long lhs1, long lhs2, long lhs3,
                                      long rhs0, long rhs1, long rhs2, long rhs3) {
        // Multiply by 0,1,2^n shortcuts
        if( (lhs1 | lhs2 | lhs3)==0 ) {
            if( lhs0==0 ) return push0();
            if( lhs0==1 ) return push(rhs0,rhs1,rhs2,rhs3);
        }
        if( (rhs1 | rhs2 | rhs3)==0 ) {
            if( rhs0==0 ) return push0();
            if( rhs0==1 ) return push(lhs0,lhs1,lhs2,lhs3);
        }

        // Multiply as shifts
        int lbc0 = Long.bitCount(lhs0), lbc1 = Long.bitCount(lhs1), lbc2 = Long.bitCount(lhs2), lbc3 = Long.bitCount(lhs3);
        if( lbc0+lbc1+lbc2+lbc3 == 1 ) {
            int shf = shf(lbc0,lbc1,lbc2,lbc3, lhs0, lhs1, lhs2, lhs3);
            return shl(shf,rhs0,rhs1,rhs2,rhs3);
        }
        int rbc0 = Long.bitCount(rhs0), rbc1 = Long.bitCount(rhs1), rbc2 = Long.bitCount(rhs2), rbc3 = Long.bitCount(rhs3);
        if( rbc0+rbc1+rbc2+rbc3 == 1 ) {
            int shf = shf(rbc0,rbc1,rbc2,rbc3, rhs0, rhs1, rhs2, rhs3);
            return shl(shf,lhs0,lhs1,lhs2,lhs3);
        }
        // Long-hand
        if( (rhs1 | rhs2 | rhs3)==0 ) {
            // Long-hand
            long val0 = lhs0*rhs0;
            long val1 = lhs1*rhs0 + Math.unsignedMultiplyHigh(lhs0,rhs0);
            long val2 = lhs2*rhs0 + Math.unsignedMultiplyHigh(lhs1,rhs0);
            long val3 = lhs3*rhs0 + Math.unsignedMultiplyHigh(lhs2,rhs0);
            return push(val0,val1,val2,val3);
        }

        throw new TODO();
    }

    // Subtract
    private ExceptionalHaltReason sub() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];

        // If both sign bits are the same and differ from the result, we overflowed
        long sub0 = lhs0 - rhs0;
        long sub1 = lhs1 - rhs1;
        if( overflowSub(lhs0,rhs0,sub0) )
            sub1--;             // Borrow
        long sub2 = lhs2 - rhs2;
        if( overflowSub(lhs1,rhs1,sub1) )
            sub2--;             // Borrow
        long sub3 = lhs3 - rhs3;
        if( overflowSub(lhs2,rhs2,sub2) )
            sub3--;             // Borrow
        // Math is mod 256, so ignore last overflow
        return push(sub0,sub1,sub2,sub3);
    }
    // Check the relationship amongst the sign bits only; the lower 63 bits are
    // computed but ignored.
    private static boolean overflowSub( long x, long y, long sum ) { return ((x^~y) & (x^sum)) < 0; }

    private int shf( int  rbc0, int  rbc1, int  rbc2, int  rbc3,
                     long rhs0, long rhs1, long rhs2, long rhs3) {
        if( rbc0!=0 ) return Long.numberOfTrailingZeros(rhs0)    ;
        if( rbc1!=0 ) return Long.numberOfTrailingZeros(rhs1)+ 64;
        if( rbc2!=0 ) return Long.numberOfTrailingZeros(rhs2)+128;
        return               Long.numberOfTrailingZeros(rhs3)+192;
    }

    // Unsigned divide
    private ExceptionalHaltReason div() {
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        // Divide by 0 or 1
        if( (lhs1 | lhs2 | lhs3)==0 ) {
            if( lhs0==0 ) return push0();
        }
        if( (rhs1 | rhs2 | rhs3)==0 ) {
            if( rhs0==0 ) return push0();
            if( rhs0==1 ) return push(lhs0,lhs1,lhs2,lhs3);
        }
        // Divide by 2^n
        int rbc0 = Long.bitCount(rhs0), rbc1 = Long.bitCount(rhs1), rbc2 = Long.bitCount(rhs2), rbc3 = Long.bitCount(rhs3);
        if( rbc0+rbc1+rbc2+rbc3 == 1 ) {
            int shf = shf(rbc0,rbc1,rbc2,rbc3, rhs0,rhs1,rhs2,rhs3);
            return shr(shf,lhs0,lhs1,lhs2,lhs3);
        }
        // Divide by self
        if( lhs0==rhs0 && lhs1==rhs1 && lhs2==rhs2 && lhs3==rhs3 )
            return push(1);

        // BigInteger fallback
        _sp += 2;               // Re-push bytes
        var dividend = new BigInteger(1, popBytes().toArrayUnsafe());
        var divisor  = new BigInteger(1, popBytes().toArrayUnsafe());
        return push(dividend.divide(divisor));
    }

    private ExceptionalHaltReason sdiv() {
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        // Divide by 0,1,2^n shortcuts
        if( (lhs1 | lhs2 | lhs3)==0 ) {
            if( lhs0==0 ) return push0();
        }
        if( (rhs1 | rhs2 | rhs3)==0 ) {
            if( rhs0==0 ) return push0();
            if( rhs0==1 ) return push(lhs0,lhs1,lhs2,lhs3);
        }
        int rbc0 = Long.bitCount(rhs0), rbc1 = Long.bitCount(rhs1), rbc2 = Long.bitCount(rhs2), rbc3 = Long.bitCount(rhs3);
        if( rbc0+rbc1+rbc2+rbc3 == 1 ) {
            int shf = shf(rbc0,rbc1,rbc2,rbc3, rhs0,rhs1,rhs2,rhs3);
            return sar(shf,lhs0,lhs1,lhs2,lhs3);
        }
        if( lhs0==rhs0 && lhs1==rhs1 && lhs2==rhs2 && lhs3==rhs3 )
            return push(1);
        throw new TODO();
    }

    private ExceptionalHaltReason mod() {
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long num0 = STK0[--_sp], num1 = STK1[_sp], num2 = STK2[_sp], num3 = STK3[_sp];
        long div0 = STK0[--_sp], div1 = STK1[_sp], div2 = STK2[_sp], div3 = STK3[_sp];
        return mod(num0,num1,num2,num3,
                   div0,div1,div2,div3);
    }
    private ExceptionalHaltReason mod(long num0, long num1, long num2, long num3,
                                      long div0, long div1, long div2, long div3) {
        // Mod by power of 2?
        int dbc0 = Long.bitCount(div0), dbc1 = Long.bitCount(div1), dbc2 = Long.bitCount(div2), dbc3 = Long.bitCount(div3);
        if( dbc0+dbc1+dbc2+dbc3 == 1 ) {
            int shf = shf(dbc0,dbc1,dbc2,dbc3, div0,div1,div2,div3);
            return mask(shf,num0,num1,num2,num3);
        }
        // Mod of self is 0
        if( num0==div0 && num1==div1 && num2==div2 && num3==div3 )
            return push(0);
        return ExceptionalHaltReason.INVALID_OPERATION;
    }

    // Just the low shf bits
    private ExceptionalHaltReason mask( int shf, long num0, long num1, long num2, long num3 ) {
        if( shf < 64 ) return push(((1L<<shf)-1) & num0, 0,0,0);
        throw new TODO();
    }


    private ExceptionalHaltReason smod() {
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        if( lhs0==rhs0 && lhs1==rhs1 && lhs2==rhs2 && lhs3==rhs3 )
            return push(1);
        return ExceptionalHaltReason.INVALID_OPERATION;
    }
    private ExceptionalHaltReason addmod() {
        var halt = useGas(_gasCalc.getMidTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 3 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        long div0 = STK0[--_sp], div1 = STK1[_sp], div2 = STK2[_sp], div3 = STK3[_sp];
        if( (div1 | div2 | div3)==0 ) {
            if( div0==0 ) return push0();
        }
        add(lhs0,lhs1,lhs2,lhs3,
            rhs0,rhs1,rhs2,rhs3);
        long num0 = STK0[--_sp], num1 = STK1[_sp], num2 = STK2[_sp], num3 = STK3[_sp];
        return mod(num0,num1,num2,num3,
                   div0,div1,div2,div3);
    }
    private ExceptionalHaltReason mulmod() {
        var halt = useGas(_gasCalc.getMidTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 3 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        long div0 = STK0[--_sp], div1 = STK1[_sp], div2 = STK2[_sp], div3 = STK3[_sp];
        if( (div1 | div2 | div3)==0 ) {
            if( div0==0 ) return push0();
        }
        mul(lhs0,lhs1,lhs2,lhs3,
            rhs0,rhs1,rhs2,rhs3);
        long num0 = STK0[--_sp], num1 = STK1[_sp], num2 = STK2[_sp], num3 = STK3[_sp];
        return mod(num0,num1,num2,num3,
                   div0,div1,div2,div3);
    }


    // Exponent
    private ExceptionalHaltReason exp() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long base0 = STK0[--_sp], base1 = STK1[_sp], base2 = STK2[_sp], base3 = STK3[_sp];
        long pow0  = STK0[--_sp],  pow1 = STK1[_sp],  pow2 = STK2[_sp],  pow3 = STK3[_sp];
        int numBits =
            pow3 != 0 ? 24*8+(64-Long.numberOfLeadingZeros(pow3)) :
            pow2 != 0 ? 16*8+(64-Long.numberOfLeadingZeros(pow2)) :
            pow1 != 0 ?  8*8+(64-Long.numberOfLeadingZeros(pow1)) :
                             (64-Long.numberOfLeadingZeros(pow0)) ;
        int numBytes = (numBits+7)>>3;
        var halt = useGas(_gasCalc.expOperationGasCost(numBytes));
        if( halt!=null ) return halt;

        if( (pow1 | pow2 | pow3) == 0 ) {
            if( pow0 == 0 )     // base^0 == 1
                return push(1,0,0,0);
            if( (base1 | base2 | base3) == 0 && 0 <= pow0 && pow0 < 256) {
                if( base0 == 0 )    // 0^pow == 0
                    return push(0,0,0,0);
                if( base0 == 1 )    // 1^pow == 1
                    return push(1,0,0,0);
                // exp2(log2(base^pow)) ==   // identity on exp(log(X))
                // exp2(pow*log2(base)) ==   // power rule
                int log2 = Long.numberOfTrailingZeros(base0);
                if( (1L<<log2)==base0 && pow0*log2 < 256 )
                    return shl((int)pow0*log2,1,0,0,0);
            }
        }
        // BigInteger fallback
        _sp += 2;               // Re-push bytes
        var base = new BigInteger(1, popBytes().toArrayUnsafe());
        var pow  = new BigInteger(1, popBytes().toArrayUnsafe());
        return push(base.modPow(pow,BonnevilleEVM.MOD_BASE));
    }

    // Sgn extend
    private ExceptionalHaltReason sign() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int x = popInt();
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        // Push the sign-extend of val, starting from byte x.  if x>=32, then v
        // is used no-change.  If x==31 then we would only extend the high byte.
        if( x >= 31 ) return null;
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        x = 31 - x;             // Shift byte to high position
        int shf = x*8;          // Bytes to bits

        // While shift is large, shift by whole registers
        while( shf >= 64 ) {
            val3 = val2;  val2 = val1;  val1 = val0;  val0 = 0;
            shf -= 64;
        }
        // Remaining partial shift has to merge across word boundaries
        if( shf != 0 ) {
            val3 = (val3<<shf) | (val2>>>(64-shf));
            val2 = (val2<<shf) | (val1>>>(64-shf));
            val1 = (val1<<shf) | (val0>>>(64-shf));
            val0 = (val0<<shf);
        }
        // Unwind shift, but sign-extend.
        // While shift is large, shift by whole registers
        shf = x*8;
        while( shf >= 64 ) {
            val0 = val1;  val1 = val2;  val2 = val3;  val3 = val3 < 0 ? -1L : 0;
            shf -= 64;
        }
        // Remaining partial shift has to merge across word boundries
        if( shf != 0 ) {
            val0 = (val0>>>shf) | (val1<<(64-shf));
            val1 = (val1>>>shf) | (val2<<(64-shf));
            val2 = (val2>>>shf) | (val3<<(64-shf));
            val3 = (val3>> shf); // Signed shift
        }

        return push(val0, val1, val2, val3);
    }

    // Pop 2 words and Unsigned compare them.  Caller safety checked.
    private int uCompareTo() {
        // CNC - Somehow this version is slower, "as if" Long.compareUnsigned did not inline
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        int          rez = Long.compareUnsigned(lhs3,rhs3);
        if( rez==0 ) rez = Long.compareUnsigned(lhs2,rhs2);
        if( rez==0 ) rez = Long.compareUnsigned(lhs1,rhs1);
        if( rez==0 ) rez = Long.compareUnsigned(lhs0,rhs0);
        return rez;

        // Slightly faster... and inlines better.
        //_sp -= 2;
        //if( STK3[_sp+1] == STK3[_sp] ) {
        //    if( STK2[_sp+1] == STK2[_sp] ) {
        //        if( STK1[_sp+1] == STK1[_sp] ) {
        //            if( STK0[_sp+1] == STK0[_sp] )
        //                return 0;
        //            return STK0[_sp+1]+Long.MIN_VALUE < STK0[_sp]+Long.MIN_VALUE ? -1 : 1;
        //
        //        } else throw new TODO();
        //    } else throw new TODO();
        //} else throw new TODO();
    }

    // Unsigned Less Than
    private ExceptionalHaltReason ult() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        return push( uCompareTo() < 0 ? 1 : 0);
    }

    // Unsigned Greater Than
    private ExceptionalHaltReason ugt() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        return push( uCompareTo() > 0 ? 1 : 0);
    }

    // Pop 2 words and Signed compare them.  Caller safety checked.
    private int sCompareTo() {
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        int          rez = Long.compare(lhs3,rhs3);
        if( rez==0 ) rez = Long.compare(lhs2,rhs2);
        if( rez==0 ) rez = Long.compare(lhs1,rhs1);
        if( rez==0 ) rez = Long.compare(lhs0,rhs0);
        return rez;
    }

    // Signed Less Than
    private ExceptionalHaltReason slt() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        return push( sCompareTo() < 0 ? 1 : 0);
    }

    // Signed Greater Than
    private ExceptionalHaltReason sgt() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        return push( sCompareTo() > 0 ? 1 : 0);
    }

    // Equals
    private ExceptionalHaltReason eq() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        return push( uCompareTo() == 0 ? 1 : 0);
    }

    // Equals zero
    private ExceptionalHaltReason eqz() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        return push( (lhs0 | lhs1 | lhs2 | lhs3)==0 ? 1L : 0L );
    }

    // And
    private ExceptionalHaltReason and() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        return push(lhs0 & rhs0, lhs1 & rhs1, lhs2 & rhs2, lhs3 & rhs3);
    }

    // Or
    private ExceptionalHaltReason or() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        return push(lhs0 | rhs0, lhs1 | rhs1, lhs2 | rhs2, lhs3 | rhs3);
    }

    // XOR
    private ExceptionalHaltReason xor() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        return push(lhs0 ^ rhs0, lhs1 & rhs1, lhs2 ^ rhs2, lhs3 ^ rhs3);
    }

    // not, bitwise complement (as opposed to a logical not)
    private ExceptionalHaltReason not() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long not0 = STK0[--_sp], not1 = STK1[_sp], not2 = STK2[_sp], not3 = STK3[_sp];
        return push(~not0, ~not1, ~not2, ~not3);
    }


    // index byte
    private ExceptionalHaltReason xbyte() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int off = 31 - popInt();
        if( off<0 || off >= 32 ) return push0();
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        while( off >= 8 ) {
            val0 = val1;  val1 = val2;  val2 = val3;  val3 = 0;
            off -= 8;
        }
        return push((val0 >> (off<<3)) & 0xff);
    }


    // Shl
    private ExceptionalHaltReason shl() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int shf = popInt();
        if( shf >= 256 )
            return push0();
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        return shl(shf,val0,val1,val2,val3);
    }

    private ExceptionalHaltReason shl( int shf, long val0, long val1, long val2, long val3 ) {
        // While shift is large, shift by whole registers
        while( shf >= 64 ) {
            val3 = val2;  val2 = val1;  val1 = val0;  val0 = 0;
            shf -= 64;
        }
        // Remaining partial shift has to merge across word boundaries
        if( shf != 0 ) {
            val3 = (val3<<shf) | (val2>>>(64-shf));
            val2 = (val2<<shf) | (val1>>>(64-shf));
            val1 = (val1<<shf) | (val0>>>(64-shf));
            val0 = (val0<<shf);
        }
        return push(val0,val1,val2,val3);
    }

    // Shr
    private ExceptionalHaltReason shr() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int shf = popInt();
        if( shf >= 256 )
            return push0();
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        return shr(shf,val0,val1,val2,val3);
    }
    private ExceptionalHaltReason shr( int shf, long val0, long val1, long val2, long val3 ) {
        // While shift is large, shift by whole registers
        while( shf >= 64 ) {
            val0 = val1;  val1 = val2;  val2 = val3;  val3 = 0;
            shf -= 64;
        }
        // Remaining partial shift has to merge across word boundries
        if( shf != 0 ) {
            val0 = (val0>>>shf) | (val1<<(64-shf));
            val1 = (val1>>>shf) | (val2<<(64-shf));
            val2 = (val2>>>shf) | (val3<<(64-shf));
            val3 = (val3>>>shf);
        }
        return push(val0,val1,val2,val3);
    }
    private ExceptionalHaltReason sar(  ) {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int shf = popInt();
        if( shf >= 256 )
            return push0();
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        return sar(shf,val0,val1,val2,val3);
    }
    private ExceptionalHaltReason sar( int shf, long val0, long val1, long val2, long val3 ) {
        // While shift is large, shift by whole registers
        while( shf >= 64 ) {
            val0 = val1;  val1 = val2;  val2 = val3;  val3 >>= 63;
            shf -= 64;
        }
        // Remaining partial shift has to merge across word boundries
        if( shf != 0 ) {
            val0 = (val0>>>shf) | (val1<<(64-shf));
            val1 = (val1>>>shf) | (val2<<(64-shf));
            val2 = (val2>>>shf) | (val3<<(64-shf));
            val3 >>= shf;
        }
        return push(val0,val1,val2,val3);
    }

    // ---------------------
    // keccak256
    private ExceptionalHaltReason keccak256() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int adr = popInt();
        int len = popInt();
        int nwords = (len+31)>>5;
        long gas = nwords*6/*FrontierGasCalculator.KECCAK256_OPERATION_WORD_GAS_COST*/
            + 30/*FrontierGasCalculator.KECCAK256_OPERATION_BASE_COST*/
            + memoryExpansionGasCost(adr,len);
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        Bytes bytes = _mem.asBytes(adr, len);
        Bytes keccak = org.hyperledger.besu.crypto.Hash.keccak256(bytes);
        assert keccak.size()==32; // Base implementation has changed?
        return push32(keccak.toArrayUnsafe());
    }


    // ---------------------
    // Call input/output

    // Recipient
    private ExceptionalHaltReason address() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getRecipientAddress());
    }

    // Balance
    private ExceptionalHaltReason balance() {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        var address = popAddress();
        boolean isSystem = _adrChk.isSystemAccount(address);
        long gas = _gasCalc.getBalanceOperationGasCost() +
            ((!isSystem && isWarm(address)) ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        if( isSystem ) return push0();

        Account account = _frame.getWorldUpdater().get(address);
        if( account == null ) return push0();
        return push((UInt256)account.getBalance().toBytes());
    }

    // Push passed originator address
    private ExceptionalHaltReason origin() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_originator);
    }

    // Push passed ETH value
    private ExceptionalHaltReason caller() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getSenderAddress());
    }

    // Push passed ETH value
    private ExceptionalHaltReason callValue() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push((UInt256)_frame.getValue().toBytes());
    }

    // Load 32bytes of the call input data
    private ExceptionalHaltReason callDataLoad() {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        int off = popInt();
        // If start is negative, or very large return a zero word
        if( off > _callData.length )
            return push0();

        // 32 bytes
        int len = Math.min(_callData.length-off,32);
        if( len == 32 )
            return push(_callData,off,32);
        // Big-endian: short data is placed high, and the low bytes are zero-filled
        long x3 = getLong(_callData, off, len);  if( 0 < len && len < 8 ) x3 <<= ((len+24)<<3); len -= 8;
        long x2 = getLong(_callData, off, len);  if( 0 < len && len < 8 ) x2 <<= ((len+24)<<3); len -= 8;
        long x1 = getLong(_callData, off, len);  if( 0 < len && len < 8 ) x1 <<= ((len+24)<<3); len -= 8;
        long x0 = getLong(_callData, off, len);  if( 0 < len && len < 8 ) x0 <<= ((len+24)<<3); len -= 8;
        return push(x0,x1,x2,x3);
    }

    // Push size of call data
    private ExceptionalHaltReason callDataSize() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push( _callData.length );
    }

    // Push call data
    private ExceptionalHaltReason callDataCopy() {
        int dstOff = popInt();
        int srcOff = popInt();
        int len    = popInt();
        var halt = useGas(copyCost(dstOff,len,3/*VERY_LOW_TIER_GAS_COST*/));
        if( halt!=null ) return halt;
        _mem.write(dstOff, _frame.getInputData(), srcOff, len );
        return null;
    }

    // Push size of code
    private ExceptionalHaltReason codeSize() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push( _codes.length );
    }

    // Copy code into Memory
    private ExceptionalHaltReason codeCopy() {
        if( _sp < 3 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int memOff = popInt();
        int srcOff = popInt();
        int len    = popInt();
        if( (memOff | srcOff | len) < 0 )
            return ExceptionalHaltReason.INVALID_OPERATION;
        if( (memOff | srcOff | len) >= Integer.MAX_VALUE )
            return ExceptionalHaltReason.INSUFFICIENT_GAS;
        var halt = useGas(copyCost(memOff, len,3));
        if( halt!=null ) return halt;

        _mem.write(memOff, _codes, srcOff, len);
        return null;
    }

    private ExceptionalHaltReason customExtCodeSize() {
        // Fail early, if we do not have cold-account gas
        var gas = _gasCalc.getExtCodeSizeOperationGasCost() + _gasCalc.getColdAccountAccessCost();
        if( _gas < gas ) return ExceptionalHaltReason.INSUFFICIENT_GAS;
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        var address = popAddress();
        // Special behavior for long-zero addresses below 0.0.1001
        if( _adrChk!=null ) {
            if( _adrChk.isNonUserAccount(address) )
                return push0();
            if( FrameUtils.contractRequired(_frame, address, _flags) && !_adrChk.isPresent(address, _frame) ) {
                //    //FrameUtils.invalidAddressContext(_frame).set(address, InvalidAddressContext.InvalidAddressType.NonCallTarget);
                //    //return ExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
                throw new TODO();
            }
        }
        return extCodeSize(address);
    }

    private ExceptionalHaltReason extCodeSize(Address address) {
        // Warmup address; true if already warm.  This is a per-transaction
        // tracking and is only for gas costs.  The actual warming happens if
        // we get past the gas test.
        long gas = _gasCalc.getExtCodeSizeOperationGasCost() +
            (isWarm(address) ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        Account acct = _frame.getWorldUpdater().get(address);
        if( acct == null ) return push0(); // No account, zero code size
        return push(acct.getCode().size());
    }

    private ExceptionalHaltReason customExtCodeCopy() {
        if( _sp < 4 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        Address address = popAddress();
        int doff = popInt();
        int soff = popInt();
        int len  = popInt();

        // Fail early, if we do not have cold-account gas
        var gas = copyCost(doff,len,0)+ _gasCalc.getColdAccountAccessCost();
        if( _gas < gas ) return ExceptionalHaltReason.INSUFFICIENT_GAS;
        // Special behavior for long-zero addresses below 0.0.1001
        if( _adrChk!=null ) {
            if( _adrChk.isNonUserAccount(address) )
                return push0();
            if( FrameUtils.contractRequired(_frame, address, _flags) && !_adrChk.isPresent(address, _frame) ) {
                //    //FrameUtils.invalidAddressContext(_frame).set(address, InvalidAddressContext.InvalidAddressType.NonCallTarget);
                //    //return ExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
                throw new TODO();
            }
        }
        return extCodeCopy(address,doff,soff,len);
    }

    private ExceptionalHaltReason extCodeCopy(Address address, int doff, int soff, int len) {
        // Warmup address; true if already warm.  This is a per-transaction
        // tracking and is only for gas costs.  The actual warming happens if
        // we get past the gas test.
        long gas = copyCost(doff,len,0) +
            (isWarm(address) ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        Account acct = _frame.getWorldUpdater().get(address);
        if( acct == null ) return null; // No account, zero code size
        _mem.write(doff,acct.getCode().toArray(),soff,len);
        return null;
    }

    private ExceptionalHaltReason customExtCodeHash() {
        // Fail early, if we do not have cold-account gas
        var gas = _gasCalc.getExtCodeSizeOperationGasCost() + _gasCalc.getColdAccountAccessCost();
        if( _gas < gas ) return ExceptionalHaltReason.INSUFFICIENT_GAS;
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        var address = popAddress();
        // Special behavior for long-zero addresses below 0.0.1001
        if( _adrChk!=null ) {
            if( _adrChk.isNonUserAccount(address) )
                return push0();
            if( FrameUtils.contractRequired(_frame, address, _flags) && !_adrChk.isPresent(address, _frame) ) {
                //    //FrameUtils.invalidAddressContext(_frame).set(address, InvalidAddressContext.InvalidAddressType.NonCallTarget);
                //    //return ExceptionalHaltReason.INVALID_SOLIDITY_ADDRESS;
                throw new TODO();
            }
        }
        return extCodeHash(address);
    }

    private ExceptionalHaltReason extCodeHash(Address address) {
        // Warmup address; true if already warm.  This is a per-transaction
        // tracking and is only for gas costs.  The actual warming happens if
        // we get past the gas test.
        long gas = _gasCalc.getExtCodeSizeOperationGasCost() +
            (isWarm(address) ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        Account acct = _frame.getWorldUpdater().get(address);
        if( acct == null ) return push0(); // No account, zero code size
        return push32(acct.getCodeHash().toArrayUnsafe());
    }

    private ExceptionalHaltReason returnDataSize() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getReturnData().size());
    }
    private ExceptionalHaltReason returnDataCopy() {
        int doff = popInt();
        int soff = popInt();
        int len  = popInt();
        var halt = useGas(copyCost(doff,len,3));
        if( halt!=null ) return halt;
        _mem.write(doff, _frame.getReturnData(), soff, len);
        return null;
    }


    // ---------------------
    // Control flow

    // Return from interpreter
    private ExceptionalHaltReason stop() {
        _frame.setOutputData(Bytes.EMPTY);
        _frame.setState(MessageFrame.State.CODE_SUCCESS);
        // Halt interpreter with no error
        return ExceptionalHaltReason.NONE;
    }

    // Return from interpreter with data
    private ExceptionalHaltReason ret() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int off = popInt();
        int len = popInt();

        var halt = useGas(memoryExpansionGasCost(off, len));
        if( halt!=null ) return halt;
        if( (off | len) < 0 || off == Integer.MAX_VALUE || len == Integer.MAX_VALUE )
            return ExceptionalHaltReason.INVALID_OPERATION;

        _frame.setOutputData(_mem.copyBytes(off, len));
        _frame.setState(MessageFrame.State.CODE_SUCCESS);
        // Halt interpreter with no error
        return ExceptionalHaltReason.NONE;
    }

    // Custom Delegate Call 6->1
    private ExceptionalHaltReason customDelegateCall(SB trace, String str) {
        preTraceCall(trace,str);
        if( FrameUtils.isHookExecution(_frame) && !isRedirectFromNativeEntity() )
            return ExceptionalHaltReason.INVALID_OPERATION;

        // BasicCustomCall.executeChecked
        if( _sp < 6 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long stipend = popLong();
        Address contract = popAddress();
        int srcOff = popInt();
        int srcLen = popInt();
        int dstOff = popInt();
        int dstLen = popInt();

        var recipient = _frame.getRecipientAddress();
        var sender    = _frame.getSenderAddress();

        return _abstractCall(trace,str, stipend, recipient, contract, sender, Wei.ZERO,false,srcOff,srcLen,dstOff,dstLen,_frame.isStatic());
    }

    private boolean isRedirectFromNativeEntity() {
        final var updater = (ProxyWorldUpdater) _frame.getWorldUpdater();
        final var recipient = updater.getHederaAccount(_frame.getRecipientAddress());
        return recipient.isTokenFacade() || recipient.isScheduleTxnFacade() || recipient.isRegularAccount();
    }

    private Address hookSender() {
        return _frame.getRecipientAddress().equals(HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS)
            ? FrameUtils.hookOwnerAddress(_frame)
            : _frame.getRecipientAddress();
    }

    // Custom Delegate Call 6->1
    private ExceptionalHaltReason customStaticCall(SB trace, String str) {
        preTraceCall(trace,str);

        // BasicCustomCall.executeChecked
        if( _sp < 6 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long stipend = popLong();
        Address recv_contract = popAddress();
        int srcOff = popInt();
        int srcLen = popInt();
        int dstOff = popInt();
        int dstLen = popInt();

        var sender = hookSender();

        return _abstractCall(trace,str, stipend, recv_contract, recv_contract, sender, Wei.ZERO,false,srcOff,srcLen,dstOff,dstLen,true);
    }

    // Revert transaction
    private ExceptionalHaltReason revert() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int off = popInt();
        int len = popInt();

        var halt = useGas(memoryExpansionGasCost(off, len));
        if( halt!=null ) return halt;

        Bytes reason = _mem.copyBytes(off, len);
        _frame.setOutputData(reason);
        _frame.setRevertReason(reason);
        _frame.setState(MessageFrame.State.REVERT);
        // Undo all warmed-up addresses this contract
        while( _warmAdrStk.size() > _warmAdrStkPtr )
            _warmAdrStk.removeLast()._warm = false;
        return ExceptionalHaltReason.NONE;
    }

    private ExceptionalHaltReason customSelfDestruct() {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        Address beneAdr = popAddress();
        Operation.OperationResult opr = _selfDestruct instanceof CustomSelfDestructOperation csdo
            ? csdo.execute(_frame, _bevm, beneAdr)
            : CustomSelfDestructOperation.executeOldSelfDestruct(_frame,beneAdr,isWarm(beneAdr), _gasCalc);
        var halt = opr.getHaltReason();
        if( halt!=null ) return halt;
        halt = useGas(opr.getGasCost());
        if( halt!=null ) return halt;
        // Successfully halting
        return ExceptionalHaltReason.NONE;
    }

    // Conditional jump to named target.  Returns either valid pc,
    // or -1 for invalid pc or -2 for out of gas
    private int jumpi(int nextpc) {
        if( _sp < 2 ) return -3;
        if( useGas(_gasCalc.getHighTierGasCost())!=null ) return -2;
        long dst  = popLong();
        long cond = popLong();
        if( cond == 0 ) return nextpc; // No jump is jump-to-nextpc
        return dst < 0 || dst >= _codes.length || jumpInvalid( (int) dst )
            ? -1                // Error
            : (int)dst;         // Target
    }

    private int jump() {
        if( _sp < 1 ) return -3;
        if( useGas(_gasCalc.getMidTierGasCost())!=null ) return -2;
        long dst = popLong();
        return dst < 0 || dst >= _codes.length || jumpInvalid( (int) dst )
            ? -1                // Error
            : (int)dst;         // Target
    }

    // Must jump to a jump dest, opcode 91/0x5B
    private boolean jumpInvalid( int x ) {
        if( _jmpDest == null ) {
            // One-time fill jmpDest cache
            _jmpDest = new BitSet();
            for( int i=0; i<_codes.length; i++ ) {
                int op = _codes[i] & 0xFF;
                if( op == 0x5B ) _jmpDest.set(i); // Set Jump Destination opcodes
                if( op >= 0x60 && op < 0x80 )
                    i += op - 0x60 + 1; // Skip immediate bytes
            }
        }
        return !_jmpDest.get( x );
    }

    private ExceptionalHaltReason noop() {
        return useGas(_gasCalc.getJumpDestOperationGasCost());
    }

    private ExceptionalHaltReason pc(int pc) {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(pc);
    }

    private ExceptionalHaltReason msize() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_mem._len);
    }

    private ExceptionalHaltReason gas() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_gas);
    }

    private ExceptionalHaltReason tLoad() {
        var halt = useGas(_gasCalc.getTransientLoadOperationGasCost());
        if( halt!=null ) return halt;
        UInt256 slot = popUInt256();
        UInt256 val = (UInt256)_frame.getTransientStorageValue(_frame.getRecipientAddress(), slot);
        return push(val);
    }

    private ExceptionalHaltReason tStore() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        if( _frame.isStatic() ) return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
        var halt = useGas(_gasCalc.getTransientStoreOperationGasCost());
        if( halt!=null ) return halt;
        UInt256 key = popUInt256();
        UInt256 val = popUInt256();
        _frame.setTransientStorageValue(_frame.getRecipientAddress(), key, val);
        return null;
    }

    private ExceptionalHaltReason mCopy() {
        if( _sp < 3 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int dst = popInt();
        int src = popInt();
        int len = popInt();
        var halt = useGas(copyCost(Math.max(src, dst),len,3));
        if( halt!=null ) return halt;
        _mem.copy(dst,src,len);
        return null;
    }

    private ExceptionalHaltReason gasPrice() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push((UInt256)_frame.getGasPrice().toBytes());
    }

    private ExceptionalHaltReason blockHash() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        var halt = useGas(_gasCalc.getBlockHashOperationGasCost());
        if( halt!=null ) return halt;
        long soughtBlock = popLong();
        if( soughtBlock == Long.MAX_VALUE )
            return push0();

        BlockValues blockValues = _frame.getBlockValues();
        long currentBlockNumber = blockValues.getNumber();
        BlockHashLookup blockHashLookup = _frame.getBlockHashLookup();
        if( !(soughtBlock >= 0L && soughtBlock < currentBlockNumber && soughtBlock >= currentBlockNumber - blockHashLookup.getLookback()) )
            return push0();
        var blockHash = blockHashLookup.apply(_frame, soughtBlock);
        return push32(blockHash.toArrayUnsafe());
    }

    private ExceptionalHaltReason coinBase() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getMiningBeneficiary() );
    }

    private ExceptionalHaltReason timeStamp() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getBlockValues().getTimestamp());
    }

    private ExceptionalHaltReason number() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getBlockValues().getNumber());
    }

    private ExceptionalHaltReason PRNGSeed() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        if( _frame.getBlockValues().getMixHashOrPrevRandao() != Bytes32.ZERO )
            throw new TODO();
        return push0();
    }

    private ExceptionalHaltReason gasLimit() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getBlockValues().getGasLimit());
    }

    private ExceptionalHaltReason customChainId() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        // Check for having a custom chain id
        var config = FrameUtils.initialFrameOf(_frame).getContextVariable(FrameUtils.CONFIG_CONTEXT_VARIABLE);
        long chainIdAsInt = config==null
            ? _chainID
            : FrameUtils.configOf(_frame).getConfigData( ContractsConfig.class).chainId();
        return push(chainIdAsInt);
    }

    private ExceptionalHaltReason selfBalance() {
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        return push((UInt256)_recv.getBalance().toBytes());
    }

    private ExceptionalHaltReason baseFee() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        final Optional<Wei> maybeBaseFee = _frame.getBlockValues().getBaseFee();
        if( maybeBaseFee.isEmpty() )
            return ExceptionalHaltReason.INVALID_OPERATION;
        return push((UInt256)maybeBaseFee.orElseThrow().toBytes());
    }

    private ExceptionalHaltReason blobHash() {
        var halt = useGas(3);
        if( halt!=null ) return halt;
        int idx = popInt();
        var maybeVerHashes = _frame.getVersionedHashes();
        if( maybeVerHashes.isEmpty() )
            return push0();
        var verHashes = maybeVerHashes.get();
        if( !(0 <= idx && idx < verHashes.size()) )
            return push0();
        var verHash = verHashes.get(idx);
        return push((UInt256)verHash.toBytes());
    }

    private ExceptionalHaltReason blobBaseFee() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        Wei blobGasPrice = _frame.getBlobGasPrice();
        return push((UInt256)blobGasPrice.toBytes());
    }

    // ---------------------
    // Memory ops

    // Memory Load
    private ExceptionalHaltReason mload() {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int adr = popInt();
        if( adr == Integer.MAX_VALUE ) return useGas(adr); // Fail, out of gas

        var halt = useGas(_gasCalc.getVeryLowTierGasCost() + memoryExpansionGasCost(adr, 32));
        if( halt!=null ) return halt;
        _mem.growMem( adr+32 );

        return push( _mem.read(adr+24), _mem.read(adr+16), _mem.read(adr+8), _mem.read(adr) );
    }

    // Memory Store
    private ExceptionalHaltReason mstore() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int adr = popInt();
        if( adr == Integer.MAX_VALUE ) return useGas(adr); // Fail, out of gas

        // Memory store gas cost from {@link FrontierGasCalculator} that is
        // decoupled from {@link MemoryFrame}.
        long gas = _gasCalc.getVeryLowTierGasCost() + memoryExpansionGasCost(adr,32);
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        _mem.write(adr, STK0[--_sp], STK1[_sp], STK2[_sp], STK3[_sp]);
        return null;
    }

    // Memory Store8 - store a *byte*
    private ExceptionalHaltReason mstore8() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int adr = popInt();
        if( adr == Integer.MAX_VALUE ) return useGas(adr); // Fail, out of gas
        long gas = 3 + memoryExpansionGasCost(adr,1);
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        _mem.write1(adr, STK0[--_sp]);
        return null;
    }

    // All arguments are in bytes
    long memoryExpansionGasCost(int adr, int len) {
        assert adr >=0 && len >= 0;  // Caller already checked
        if( adr+len < 0 ) return Integer.MAX_VALUE; // Overflow gas cost
        if( adr+len <= _mem._len ) return 0;        // No extension, so no memory cost
        if( len==0 ) return 0; // No memory accessed, so no memory cost
        long pre  = memoryCost(_mem._len );
        long post = memoryCost(adr + len );
        return post - pre;
    }

    // A version of {@link FrontierGasCalculator.memoryCost} from (used through
    // at least {@link CancunGasCalculator}) using a {@code int} for length.
    // Values larger than an int will fail for gas usage first.
    // Values int or smaller will never overflow a long, and so do not need
    // range checks or clamping.
    private long memoryCost(int len) {
        int nwords = (len+31)>>5;
        long words2 = (long)nwords*nwords;
        long base = words2>>9;  // divide 512
        return base + nwords*_gasCalc.getVeryLowTierGasCost()/*FrontierGasCalculator.MEMORY_WORD_GAS_COST*/;
    }

    private long copyCost(int off, int len, int base) {
        int nwords = (len+31)>>5;
        return 3L/*COPY_WORD_GAS_COST*/*nwords+base+ memoryExpansionGasCost(off, len);
    }

    // ---------------------
    // Permanent Storage ops.

    // Storage "slots" are keyed by (Address,UInt256) and are cold until first
    // touched.  "cold" is reset at top-level contracts, and "warm" is passed
    // down to all child contract calls.  Reverted contracts undo their
    // "warming" touches as-if never happened.

    private final ArrayList<AdrKey> _freeAK = new ArrayList<>();
    private final HashMap<AdrKey,AdrKey> _internAK = new HashMap<>();
    // Is address+key warmed up?  Used for gas calcs
    private static class AdrKey {
        Address _adr;   // The pair (Address,UInt256) are a Key for the warm/cold check
        UInt256 _ui256; //
        boolean _warm;  // Warm-up flag
        @Override public int hashCode() { return _adr.hashCode() ^ (_ui256==null ? -1 : _ui256.hashCode()); }
        @Override public boolean equals( Object o ) {
            if( !(o instanceof AdrKey ak) ) return false;
            return _adr.equals(ak._adr) && _ui256==ak._ui256;
        }
    }
    // Get and intern a slot based on Address and key
    private AdrKey getSlot( Address adr, long key0, long key1, long key2, long key3) {
        return getSlot(adr, UI256.intern(key0,key1,key2,key3));
    }
    private AdrKey getSlot( Address adr, UInt256 key) {
        AdrKey ak = _freeAK.isEmpty() ? new AdrKey() : _freeAK.removeLast();
        ak._adr = adr;
        ak._ui256 = key;
        AdrKey ak2 = _internAK.get(ak);
        if( ak2 != null ) _freeAK.add(ak);
        else              _internAK.put((ak2=ak),ak);
        return ak2;
    }
    private boolean allColdSlots() {
        for( AdrKey ak : _internAK.keySet() )
            if( ak._warm )
                return false;
        return true;
    }

    // Is AdrKey warm?  If not, warm it and stack it in case of revert
    private boolean isWarm( AdrKey ak ) {
        if( ak._warm ) return true;
        _warmAdrStk.add(ak);    // Record for revert
        ak._warm = true;
        return false;           // Was cold, but warmed-up afterwards
    }
    // Is Address warm?  Make a AdrKey with null UInt256 and check normally
    private boolean isWarm( Address adr ) {
        return _gasCalc.isPrecompile(adr) || isWarm(getSlot(adr,null));
    }


    // Load from the global/permanent store
    private ExceptionalHaltReason sLoad() {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long key0 = STK0[--_sp], key1 = STK1[_sp], key2 = STK2[_sp], key3 = STK3[_sp];

        // Warmup address; true if already warm.  This is a per-transaction
        // tracking and is only for gas costs.  The actual warming happens if
        // we get past the gas test.
        AdrKey ak = getSlot(_recv.getAddress(), key0, key1, key2, key3);
        long gas = _gasCalc.getSloadOperationGasCost() +
            (isWarm(ak) ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdSloadCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        // UInt256 already in AdrKey
        UInt256 val = _recv.getStorageValue( ak._ui256 );
        return push( val );
    }

    private ExceptionalHaltReason customSLoad( ) {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        // Read key before SLOAD replaces it with value
        long key0 = STK0[_sp-1], key1 = STK1[_sp-1], key2 = STK2[_sp-1], key3 = STK3[_sp-1];

        var halt = sLoad();
        if( halt==null && _isSidecarEnabled && _tracker != null ) {
            // The base SLOAD operation returns its read value on the stack
            long val0 = STK0[_sp-1], val1 = STK1[_sp-1], val2 = STK2[_sp-1], val3 = STK3[_sp-1];
            UInt256 key = UI256.intern(key0,key1,key2,key3);
            UInt256 val = UI256.intern(val0,val1,val2,val3);
            _tracker.trackIfFirstRead(_contractId, key, val);
        }
        return halt;
    }

    private ExceptionalHaltReason sStore() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long key0 = STK0[--_sp], key1 = STK1[_sp], key2 = STK2[_sp], key3 = STK3[_sp];
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];

        if( _recvMutable == null )  return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;

        // Attempt to write to read-only
        if( _frame.isStatic() ) return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;

        // Intern and wrap key
        UInt256 key = UI256.intern(key0, key1, key2, key3);
        UInt256 val = UI256.intern(val0, val1, val2, val3);
        _wrap0._u = _recvMutable.getStorageValue(key);
        _wrap1._u = _recvMutable.getOriginalStorageValue(key);

        // Warmup address; true if already warm.  This is a per-transaction
        // tracking and is only for gas costs.  The actual warming happens if
        // we get past the gas test.
        AdrKey ak = getSlot(_recv.getAddress(), key);
        long gas = _gasCalc.calculateStorageCost(val, _wrap0, _wrap1) +
            (isWarm(ak) ? 0 : _gasCalc.getColdSloadCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        // Increment the refund counter.
        _frame.incrementGasRefund(_gasCalc.calculateStorageRefundAmount(val, _wrap0, _wrap1));
        // Do the store
        _recvMutable.setStorageValue(key, val);
        _lastSKey = key;
        _lastSVal = val;
        return null;
    }

    private ExceptionalHaltReason customSStore() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        // Read key and value before SSTORE pops it
        long key0 = STK0[_sp-1], key1 = STK1[_sp-1], key2 = STK2[_sp-1], key3 = STK3[_sp-1];
        long val0 = STK0[_sp-2], val1 = STK1[_sp-2], val2 = STK2[_sp-2], val3 = STK3[_sp-2];

        var halt = sStore();
        if( halt==null && _isSidecarEnabled && _tracker != null ) {
            UInt256 key = UI256.intern(key0,key1,key2,key3);
            UInt256 val = UI256.intern(val0,val1,val2,val3);
            _tracker.trackIfFirstRead(_contractId, key, val);
        }
        return halt;
    }

    // ---------------------
    // Simple stack ops

    // Pop
    private ExceptionalHaltReason pop(  ) {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        _sp--;
        return null;
    }

    private ExceptionalHaltReason push0Op() {
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
        long x0 = STK0[_sp-n], x1 = STK1[_sp-n], x2 = STK2[_sp-n], x3 = STK3[_sp-n];
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
    private ExceptionalHaltReason customLog(int ntopics) {
        if( _sp < ntopics ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int off = popInt();
        int len = popInt();
        if( _frame.isStatic() ) return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
        var halt = useGas(375 +           // Frontier.LOG_OPERATION_BASE_GAS_COST,
                          len * 8L +      // Frontier.LOG_OPERATION_DATA_BYTE_GAS_COST
                          ntopics * 375 + // Frontier.LOG_OPERATION_TOPIC_GAS_COST
                          memoryExpansionGasCost(off,len));
        if( halt!=null ) return halt;
        Bytes data = _mem.copyBytes(off,len); // Copy, since backing Memory will be crushed by later bytecodes

        ImmutableList.Builder<LogTopic> builder = ImmutableList.builderWithExpectedSize(ntopics);
        for( int i = 0; i < ntopics; i++ )
            builder.add(LogTopic.create(popBytes()));

        // Since these are consumed by mirror nodes, which always want to know the Hedera id
        // of the emitting contract, we always resolve to a long-zero address for the log
        var loggerAddress = ConversionUtils.longZeroAddressOfRecipient(_frame);
        _frame.addLog(new Log(loggerAddress, data, builder.build()));

        return null;
    }

    private ExceptionalHaltReason create( SB trace, String str, boolean hasSalt ) {
        // Nested create contract call; so print the post-trace before the
        // nested call, and reload the pre-trace state after call.
        preTraceCall(trace,str);
        if( _sp < (hasSalt ? 4 : 3) ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        if( _frame.isStatic() ) return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        Wei value = Wei.wrap(UI256.intern(val0,val1,val2,val3));
        // Memory size for code and gas
        int off = popInt();
        int len = popInt();
        int dstOff = 0;
        int dstLen = 0;
        Bytes salt = hasSalt ? popBytes() : null;

        // Custom-only hook
        Address sender = hookSender();

        _frame.clearReturnData();

        var senderAccount = _frame.getWorldUpdater().getAccount(sender);
        if( value.compareTo(senderAccount.getBalance()) > 0 || _frame.getDepth() >= 1024/*AbstractCustomCreateOperation.MAX_STACK_DEPTH*/)
            return push0();
        long senderNonce = senderAccount.getNonce();
        senderAccount.incrementNonce(); // Post increment

        Code code = _bevm.getCodeUncached(_mem.asBytes(off,len));

        Address contract;
        if( hasSalt ) {
            Bytes bytes = Bytes.concatenate(new Bytes[]{BonnevilleEVM.CREATE2_PREFIX, sender, salt, code.getCodeHash()});
            Bytes32 hash = org.hyperledger.besu.crypto.Hash.keccak256(bytes);
            contract = Address.extract(hash);

        } else {
            contract = Address.contractAddress(sender, senderNonce);
        }
        assert contract != null;

        if( _frame.getWorldUpdater() instanceof ProxyWorldUpdater proxyUpdater )
            proxyUpdater.setupInternalAliasedCreate(sender, contract);
        isWarm(contract);

        // gas cost for making the contract
        long gas = _gasCalc.txCreateCost() + // 32000
            _gasCalc.initcodeCost(len) + // Shanghai: words(544)*2 == 16 words *2 = 32
            memoryExpansionGasCost(off,len); // 63-63==0
        if( hasSalt ) gas += _gasCalc.createKeccakCost(len);
        var halt = useGas(gas);
        if( halt != null ) return null;
        // Gas for child
        long childGasStipend = _gasCalc.gasAvailableForChildCreate(_gas);
        _gas -= childGasStipend;
        _frame.setGasRemaining(_gas);

        Bytes src = Bytes.EMPTY;


        // ----------------------------
        // child frame is added to frame stack via build method
        MessageFrame child = MessageFrame.builder()
            .parentMessageFrame(_frame)
            .type(MessageFrame.Type.CONTRACT_CREATION)
            .initialGas(childGasStipend)
            .address(contract)
            .contract(contract)
            .inputData(src)
            .sender(sender)
            .value(value)
            .apparentValue(value)
            .code(code)
            .isStatic(false)
            .completer(child0 -> {} )
            .build();
        _frame.setState(MessageFrame.State.CODE_SUSPENDED);

        // Frame lifetime management
        PublicContractCreationProcessor msg = _bevm._create;
        assert child.getState() == MessageFrame.State.NOT_STARTED;
        _tracing.traceContextEnter(child);
        msg.start(child, _tracing);

        if( child.getState() == MessageFrame.State.CODE_EXECUTING ) {
            // ----------------------------
            // Recursively call
            _bevm.runToHalt(this,child,_tracing);
            // ----------------------------
        }

        switch( child.getState() ) {
        case MessageFrame.State.CODE_SUSPENDED:    throw new TODO("Should not reach here");
        case MessageFrame.State.CODE_SUCCESS:      msg.codeSuccess(child, _tracing);  break; // Sets COMPLETED_SUCCESS
        case MessageFrame.State.REVERT:            msg.revert(child);  break;
        case MessageFrame.State.COMPLETED_SUCCESS: throw new TODO("cant find who sets this");
        case MessageFrame.State.COMPLETED_FAILED:  throw new TODO("cant find who sets this");
        case MessageFrame.State.EXCEPTIONAL_HALT:  {
            FrameUtils.exceptionalHalt(child);
            var haltReason = child.getExceptionalHaltReason().get();
            // Fairly obnoxious back-door signal to propagate a particular
            // failure 1 call level, or not.  From CustomMessageCallProcessor:324
            var maybeFailureToPropagate = FrameUtils.getAndClearPropagatedCallFailure(_frame);
            if( maybeFailureToPropagate != HevmPropagatedCallFailure.NONE ) {
                assert maybeFailureToPropagate.exceptionalHaltReason().get() == haltReason;
                halt = haltReason;
            }
            // These propagate indefinitely, killing the code at the top level.
            if( haltReason == CustomExceptionalHaltReason.INVALID_CONTRACT_ID ||
                haltReason == CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS )
                halt = haltReason;
            break;
        }
        default: throw new TODO();
        };

        _tracing.traceContextExit(child);
        child.getWorldUpdater().commit();
        child.getMessageFrameStack().removeFirst(); // Pop child frame

        // See AbstractCustomCreateOperation.complete
        _mem.write(dstOff, child.getOutputData(), 0, dstLen);
        _frame.setReturnData(child.getOutputData());
        _frame.addLogs(child.getLogs());
        _frame.addCreates(child.getCreates());
        _frame.addSelfDestructs(child.getSelfDestructs());
        _frame.incrementGasRefund(child.getGasRefund());

        _gas += child.getRemainingGas(); // Refund unused child gas

        if( trace != null )
            System.out.println(trace.clear().p("RETURN ").p(str).nl());
        push(child.getState() == MessageFrame.State.COMPLETED_SUCCESS ? child.getContractAddress() : null);
        return halt;
    }

    public void complete( MessageFrame frame, MessageFrame child ) {/*nothing*/}

    private ExceptionalHaltReason customCreate2(SB trace, String str) {
        if( !(_frame.getWorldUpdater() instanceof ProxyWorldUpdater)  || !_flags.isCreate2Enabled(_frame) )
            return ExceptionalHaltReason.INVALID_OPERATION;
        throw new TODO();
    }

    // Call Code - same as customCall except skips the custom mustBePresent check
    private ExceptionalHaltReason callCode(SB trace, String str) {
        // Nested create contract call; so print the post-trace before the
        // nested call, and reload the pre-trace state after call.
        preTraceCall(trace,str);
        if( FrameUtils.isHookExecution(_frame) && !isRedirectFromNativeEntity() )
            return ExceptionalHaltReason.INVALID_OPERATION;

        if( _sp < 7 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long stipend = popLong();
        Address contract = popAddress();
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        Wei value = Wei.wrap(UI256.intern(val0,val1,val2,val3));
        boolean hasValue = (val0 | val1 | val2 | val3) != 0;
        int srcOff = popInt();
        int srcLen = popInt();
        int dstOff = popInt();
        int dstLen = popInt();

        var recv_send = hookSender();

        return _abstractCall(trace,str, stipend, recv_send, contract, recv_send, value,hasValue,srcOff,srcLen,dstOff,dstLen,_frame.isStatic());
    }

    // CustomCallOperation
    private ExceptionalHaltReason customCall(SB trace, String str) {
        // Nested create contract call; so print the post-trace before the
        // nested call, and reload the pre-trace state after call.
        preTraceCall(trace,str);

        if( _sp < 7 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long stipend = popLong();
        Address recv_contract = popAddress();
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        Wei value = Wei.wrap(UI256.intern(val0,val1,val2,val3));
        boolean hasValue = (val0 | val1 | val2 | val3) != 0;
        int srcOff = popInt();
        int srcLen = popInt();
        int dstOff = popInt();
        int dstLen = popInt();
        // yup, child frame sender is this frames' recipient
        var sender = hookSender();

        return _abstractCall(trace,str, stipend, recv_contract, recv_contract, sender, value,hasValue,srcOff,srcLen,dstOff,dstLen,_frame.isStatic());
    }


    private ExceptionalHaltReason _abstractCall(SB trace, String str, long stipend, Address recipient, Address contract, Address sender, Wei value, boolean hasValue, int srcOff, int srcLen, int dstOff, int dstLen, boolean isStatic ) {
        Account contractAccount = _frame.getWorldUpdater().get(contract);

        // gas cost check.  As usual, the input values are capped at
        // Integer.MAX_VALUE and the sum of a few of these will not overflow a
        // long and large gas values will just fail the gas-available check -
        // hence no need for overflow math.
        long srcCost = memoryExpansionGasCost(srcOff, srcLen);
        long dstCost = memoryExpansionGasCost(dstOff, dstLen);
        long memCost = Math.max(srcCost, dstCost);
        long gas = _gasCalc.callOperationBaseGasCost() + memCost;
        if( hasValue )
            gas += _gasCalc.callValueTransferGasCost();
        if( (contractAccount == null || contractAccount.isEmpty()) && hasValue )
            gas += _gasCalc.newAccountGasCost();
        if( (recipient == null || recipient.isEmpty()) && hasValue )
            throw new TODO();
        // Check the cold account cost, but do not charge
        if( _gas < gas+ (isStatic ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost()) )
            return ExceptionalHaltReason.INSUFFICIENT_GAS;

        if( mustBePresent(contract, hasValue) ) {
        //    FrameUtils.invalidAddressContext(_frame).set(to, InvalidAddressContext.InvalidAddressType.InvalidCallTarget);
        //    return new OperationResult(cost, INVALID_SOLIDITY_ADDRESS);
            throw new TODO();
        }

        // CallOperation
        if( isStatic && hasValue )
            return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;

        // AbstractCallOperation

        // There is a 2nd gas check made here, with account possibly being warm
        // which is lower cost, so never fails...
        gas += isWarm(contract)
            ? _gasCalc.getWarmStorageReadCost()
            : _gasCalc.getColdAccountAccessCost();
        // Charge gas for call
        var halt = useGas(gas);
        assert halt==null;      // always works because codeAccount check above is larger

        _frame.clearReturnData();

        // Not sure how this can be set
        if( contractAccount!=null && contractAccount.hasDelegatedCode() )
            throw new TODO();

        Bytes src = _mem.asBytes(srcOff, srcLen);

        // If the call is sending more value than the account has or the
        // message frame is to deep return a failed call
        if( value.compareTo(_recv.getBalance()) > 0 || _frame.getDepth() >= 1024 )
            return push0();

        // Get the bytecodes to execute
        Code code = CodeV0.EMPTY_CODE;

        // If has a contract account, get code from it
        if( contractAccount!=null ) {
            code = _bevm.getCode(contractAccount.getCodeHash(), contractAccount.getCode());
            if( !code.isValid() )
                throw new TODO();
        }

        // gasAvailableForChildCall; this is the "all but 1/64th" computation
        long gasCap = Math.min(_gas - (_gas>>6),stipend);
        if( _gasTangerineWhistle )
            _gas -= gasCap;
        else
            throw new TODO();   // Make sure FrontierGas calc is sane
        _frame.setGasRemaining(_gas);
        // Child getting value also gets a gas stipend
        if( hasValue ) gasCap += _gasCalc.getAdditionalCallStipend();

        // ----------------------------
        // child frame is added to frame stack via build method
        MessageFrame child = MessageFrame.builder()
            .parentMessageFrame(_frame)
            .type(MessageFrame.Type.MESSAGE_CALL)
            .initialGas(gasCap)
            .address(recipient)
            .contract(contract)
            .inputData(src)
            .sender(sender)
            .value(value)
            .apparentValue(value)
            .code(code)
            .isStatic(isStatic)
            .completer( child0 -> {} )
            .build();
        _frame.setState(MessageFrame.State.CODE_SUSPENDED);

        // Frame lifetime management
        assert child.getState() == MessageFrame.State.NOT_STARTED;
        _tracing.traceContextEnter(child);
        PublicMessageCallProcessor msg = _bevm._call;

        // More frame safety checks
        msg.start(child, _tracing);

        if( child.getState() == MessageFrame.State.CODE_EXECUTING ) {
            // ----------------------------
            // Recursively call
            _bevm.runToHalt(this,child,_tracing);
            // ----------------------------
        }

        switch( child.getState() ) {
        case MessageFrame.State.CODE_SUSPENDED:    throw new TODO("Should not reach here");
        case MessageFrame.State.CODE_SUCCESS:      msg.codeSuccess(child, _tracing);  break; // Sets COMPLETED_SUCCESS
        case MessageFrame.State.REVERT:            msg.revert(child);  break;
        case MessageFrame.State.COMPLETED_SUCCESS: break; // Precompiled sys contracts hit here
        case MessageFrame.State.COMPLETED_FAILED:  throw new TODO("cant find who sets this");
        case MessageFrame.State.EXCEPTIONAL_HALT:  {
            FrameUtils.exceptionalHalt(child);
            var haltReason = child.getExceptionalHaltReason().get();
            // Fairly obnoxious back-door signal to propagate a particular
            // failure 1 call level, or not.  From CustomMessageCallProcessor:324
            var maybeFailureToPropagate = FrameUtils.getAndClearPropagatedCallFailure(_frame);
            if( maybeFailureToPropagate != HevmPropagatedCallFailure.NONE ) {
                assert maybeFailureToPropagate.exceptionalHaltReason().get() == haltReason;
                halt = haltReason;
            }
            // These propagate indefinitely, killing the code at the top level.
            if( haltReason == CustomExceptionalHaltReason.INVALID_CONTRACT_ID ||
                haltReason == CustomExceptionalHaltReason.INSUFFICIENT_CHILD_RECORDS )
                halt = haltReason;
            break;
        }
        default: throw new TODO();
        };

        _tracing.traceContextExit(child);
        child.getWorldUpdater().commit();
        child.getMessageFrameStack().removeFirst(); // Pop child frame

        // See AbstractCallOperation.complete
        _mem.write(dstOff, child.getOutputData(), 0, dstLen);
        _frame.setReturnData(child.getOutputData());
        _frame.addLogs(child.getLogs());
        _frame.addCreates(child.getCreates());
        _frame.addSelfDestructs(child.getSelfDestructs());
        _frame.incrementGasRefund(child.getGasRefund());

        _gas += child.getRemainingGas(); // Recover leftover gas from child

        if( trace != null )
            System.out.println(trace.clear().p("RETURN ").p(str).nl());
        push(child.getState()==MessageFrame.State.COMPLETED_SUCCESS ? 1 : 0);
        return halt;
    }

    // This call will create the "to" address, so it doesn't need to be present
    private boolean mustBePresent( Address to, boolean hasValue ) {
        return !ConversionUtils.isLongZero(to)
            && hasValue
            && _adrChk!=null
            && !_adrChk.isPresent(to, _frame)
            && _flags.isImplicitCreationEnabled()
            // Let system accounts calls or if configured to allow calls to
            // non-existing contract address calls go through so the message
            // call processor can fail in a more legible way
            && !_adrChk.isSystemAccount(to)
            && FrameUtils.contractRequired(_frame, to, _flags);
    }

}
