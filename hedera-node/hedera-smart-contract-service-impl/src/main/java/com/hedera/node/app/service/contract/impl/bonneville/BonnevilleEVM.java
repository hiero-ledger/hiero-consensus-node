// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.streams.SidecarType;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomCreateOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomExtCodeSizeOperation;
import com.hedera.node.app.service.contract.impl.exec.operations.CustomSelfDestructOperation;
import com.hedera.node.app.service.contract.impl.exec.processors.*;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HederaSystemContract;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract;
import com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils;
import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker;
import com.hedera.node.app.service.contract.impl.state.ProxyWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.app.service.contract.impl.utils.TODO;

import com.google.common.collect.ImmutableList;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.*;

// BESU imports
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.crypto.Hash;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.Code;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.code.CodeV0;
import org.hyperledger.besu.evm.frame.ExceptionalHaltReason;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.log.Log;
import org.hyperledger.besu.evm.log.LogTopic;
import org.hyperledger.besu.evm.operation.Operation;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.tracing.OperationTracer;

public class BonnevilleEVM extends HEVM {
    @NonNull final FeatureFlags _flags;
    final boolean _hasOpers;    // Only required because EVM is BESU-private

    public BonnevilleEVM(
            @NonNull OperationRegistry operations,
            @NonNull GasCalculator gasCalc,
            @NonNull EvmConfiguration evmConfiguration,
            @NonNull EvmSpecVersion evmSpecVersion,
            @NonNull FeatureFlags featureFlags) {
        super(operations, gasCalc, evmConfiguration, evmSpecVersion );
        _flags = featureFlags;
        _hasOpers = operations != null;
    }

    @Override
    public void runToHalt(@NotNull MessageFrame frame, @NotNull OperationTracer tracing) {
        // Run the contract bytecodes
        new BEVM(this,frame,tracing,_hasOpers ? getOperationsUnsafe() : null).run();
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
        if( op == 0xF3 ) return "ret ";
        if( op == 0xF4 ) return "dCal";
        if( op == 0xF5 ) return "Crt2";
        if( op == 0xFA ) return "sCal";
        if( op == 0xFD ) return "revert ";
        if( op == 0xFE ) return "invalid ";
        if( op == 0xFF ) return "self-destruct ";
        return String.format("%x",op);
    }
    private static final String[] OPNAMES = new String[]{
        /* 00 */ "stop", "add ", "mul ", "sub ", "div ", "sdiv", "mod ", "smod", "amod", "mmod", "exp ", "sign", "0C  ", "0D  ", "0E  ", "0F  ",
        /* 10 */ "ult ", "ugt ", "slt ", "sgt ", "eq  ", "eq0 ", "and ", "or  ", "18  ", "not ", "1A  ", "shl ", "shr ", "1D  ", "1E  ", "1F  ",
        /* 20 */ "kecc", "21  ", "22  ", "23  ", "24  ", "25  ", "26  ", "27  ", "28  ", "29  ", "2A  ", "2B  ", "2C  ", "2D  ", "2E  ", "2F  ",
        /* 30 */ "addr", "31  ", "orig", "calr", "cVal", "Load", "Size", "Data", "cdSz", "Copy", "3A  ", "gasP", "3C  ", "retZ", "retC", "hash",
        /* 40 */ "40  ", "Coin", "42  ", "43  ", "seed", "limi", "chid", "47  ", "fee ", "49  ", "4A  ", "4B  ", "4C  ", "4D  ", "4E  ", "4F  ",
        /* 50 */ "pop ", "mld ", "mst ", "53  ", "Csld", "Csst", "jmp ", "jmpi", "58  ", "59  ", "gas ", "noop", "5C  ", "5D  ", "5E  ", "psh0",
        };
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
    @NotNull BonnevilleEVM _bevm;
    @NonNull final MessageFrame _frame;

    @NonNull final GasCalculator _gasCalc;

    // Contract bytecodes
    final byte[] _codes;

    // Gas available, runs down to zero
    private final long _startGas;
    private long _gas;

    // Recipient
    @NonNull Account _recv;     // Always have a readable aocount
    MutableAccount _recvMutable;// Sometimes a writable account

    // Temp per-contract storage
    final Memory _mem;

    // Custom SLoad asks this global question
    final boolean _isSidecarEnabled;
    // Custom SLoad optional tracking
    final StorageAccessTracker _tracker;
    //
    final ContractID _contractId;

    // Custom ExtCodeSize needs this
    final FeatureFlags _flags;
    final AddressChecks _adrChk;

    // Custom Create needs this
    final CustomCreateOperation _cccp;

    // Custom Self-Destruct
    final CustomSelfDestructOperation _selfDestruct;


    // Wrap a UInt256 in a Supplier for short-term usage, without allocating
    // per-bytecode.
    final UI256.Wrap _wrap0 = new UI256.Wrap(), _wrap1 = new UI256.Wrap();

    // Last Storage key, value.  Used to inform the Frame that storage was updated.
    UInt256 _lastSKey, _lastSVal;

    // Input data
    byte[] _callData;

    @NotNull final OperationTracer _tracing;

    BEVM( @NotNull BonnevilleEVM bevm, @NotNull MessageFrame frame, @NotNull OperationTracer tracing, @NotNull Operation[] operations ) {
        _bevm = bevm;

        _frame = frame;

        _flags = bevm._flags;   // Feature Flags

        // Bytecodes
        _codes = frame.getCode().getBytes().toArrayUnsafe();
        if( frame.getCode().getEofVersion() != 0 )
            throw new TODO("Expect eofVersion==0");

        _gasCalc = bevm.getGasCalculator();
        // If SSTore minimum gas is ever not-zero, will need to check in sStore
        if( _gasCalc.getVeryLowTierGasCost() > 10 )
            throw new TODO("Need to restructure how gas is computed");

        // Starting and current gas
        _startGas = frame.getRemainingGas();
        _gas = _startGas;

        // Account receiver
        _recv        = frame.getWorldUpdater().get       (frame.getRecipientAddress());
        _recvMutable = frame.getWorldUpdater().getAccount(frame.getRecipientAddress());
        assert _recv != null;

        // Local temp storage
        _mem = new Memory();

        // Hedera custom sidecar
        _isSidecarEnabled = bevm._hasOpers && _flags.isSidecarEnabled(frame, SidecarType.CONTRACT_STATE_CHANGE);
        // Hedera optional tracking first SLOAD
        _tracker = FrameUtils.accessTrackerFor(frame);

        // Address Check
        CustomExtCodeSizeOperation cExt = bevm._hasOpers ? (CustomExtCodeSizeOperation)operations[0x3B] : null;
        _adrChk = bevm._hasOpers ? cExt.addressChecks : null;
        //if( cExt.enableEIP3540 )
        //    throw new TODO();   // Assumed never in customExtCodeSize

        // Custom Contract Creation Processor
        _cccp = bevm._hasOpers ? (CustomCreateOperation)operations[0xF0] : null;
        // Custom Self-Destruct hook
        _selfDestruct = bevm._hasOpers ? (CustomSelfDestructOperation)operations[0xFF] : null;


        // Preload input data
        _callData = _frame.getInputData().toArrayUnsafe();

        _contractId = bevm._hasOpers ? FrameUtils.proxyUpdaterFor(_frame).getHederaContractId(_frame.getRecipientAddress()) : null;

        _tracing = tracing;
    }

    public BEVM run() {
        // TODO: setup
        var halt = _run();
        if( halt != ExceptionalHaltReason.NONE ) {
            _frame.setExceptionalHaltReason(Optional.of(halt));
            _frame.setState(MessageFrame.State.EXCEPTIONAL_HALT);
        }
        return this;
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

    private ExceptionalHaltReason push( Address adr ) {
        return adr==null ? push0() : push(adr.toArrayUnsafe(),0,20);
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
    // TODO, common case, optimize
    private ExceptionalHaltReason push32( byte[] src ) {
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

    // -----------------------------------------------------------
    // Execute bytecodes until done
    ExceptionalHaltReason _run() {
        if( _codes.length==0 )
            return stop();
        SB trace = null; // new SB();
        PrintStream oldSysOut = System.out;
        if( trace != null )
            System.setOut(new PrintStream(new FileOutputStream( FileDescriptor.out)));

        int pc = 0;
        ExceptionalHaltReason halt = null;

        while( halt==null ) {
            if( pc == _codes.length )
                { halt = stop(); break; }
            int op = _codes[pc] & 0xFF;
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
            case 0x10 -> ult();
            case 0x11 -> ugt();
            case 0x12 -> slt();
            case 0x13 -> sgt();
            case 0x14 -> eq ();
            case 0x15 -> eqz();
            case 0x16 -> and();
            case 0x17 -> or ();
            case 0x19 -> not();
            case 0x1B -> shl();
            case 0x1C -> shr();

            case 0x20 -> keccak256();

            // call/input/output arguments
            case 0x30 -> address();
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

            case 0x41 -> coinBase();
            case 0x44 -> PRNGSeed();
            case 0x45 -> gasLimit();
            case 0x46 -> customChainId();
            case 0x48 -> baseFee();

            case 0x50 -> pop();

            // Memory, Storage
            case 0x51 -> mload();
            case 0x52 -> mstore();
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

            case 0x5A -> gas();
            case 0x5B -> noop();// Jump Destination, a no-op

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

            case 0xF0 -> customCreate      (trace,"CREATE"  );
            case 0xF1 -> customCall        (trace,"CALL"    );
            case 0xF3 -> ret();
            case 0xF4 -> customDelegateCall(trace,"DELEGATE");
            case 0xF5 -> customCreate2     (trace,"CREATE2" );
            case 0xFA -> customStaticCall  (trace,"STATIC"  );
            case 0xFD -> revert();
            case 0xFF -> customSelfDestruct();

            default ->   ExceptionalHaltReason.INVALID_OPERATION;
            };

            if( trace != null ) {
                postTrace(trace);
                if( halt!=null )
                    trace.p(" ").p(halt.toString());
                System.out.println(trace);
                trace.clear();
            }
        }

        if( trace != null ) {
            System.out.println();
            System.setOut(oldSysOut);
        }

        // Set state into Frame
        _frame.setPC(pc);
        _frame.setGasRemaining(_gas);
        if( _lastSKey != null )
            _frame.storageWasUpdated(_lastSKey, _lastSVal );

        return halt;
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

    // ---------------------
    // Arithmetic

    // Add
    private ExceptionalHaltReason add() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];

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
        // Multiply by 0,1,2^n shortcuts
        if( (lhs1 | lhs2 | lhs3)==0 ) {
            if( lhs0==0 ) return push0();
            if( lhs0==1 ) return push(rhs0,rhs1,rhs2,rhs3);
        }
        if( (rhs1 | rhs2 | rhs3)==0 ) {
            if( rhs0==0 ) return push0();
            if( rhs0==1 ) return push(lhs0,lhs1,lhs2,lhs3);
        }

        int lbc0 = Long.bitCount(lhs0), lbc1 = Long.bitCount(lhs1), lbc2 = Long.bitCount(lhs2), lbc3 = Long.bitCount(lhs3);
        if( lbc0+lbc1+lbc2+lbc3 == 1 ) {
            int shf;
            if( lbc0==1 )      shf =     Long.numberOfTrailingZeros(lhs0);
            else if( lbc1==1 ) shf =  64+Long.numberOfTrailingZeros(lhs1);
            else if( lbc2==1 ) shf = 128+Long.numberOfTrailingZeros(lhs2);
            else               shf = 192+Long.numberOfTrailingZeros(lhs3);
            return shl(shf,rhs0,rhs1,rhs2,rhs3);
        }
        int rbc0 = Long.bitCount(rhs0), rbc1 = Long.bitCount(rhs1), rbc2 = Long.bitCount(rhs2), rbc3 = Long.bitCount(rhs3);
        if( rbc0+rbc1+rbc2+rbc3 == 1 ) {
            int shf;
            if( rbc0==1 )      shf =     Long.numberOfTrailingZeros(rhs0);
            else if( rbc1==1 ) shf =  64+Long.numberOfTrailingZeros(rhs1);
            else if( rbc2==1 ) shf = 128+Long.numberOfTrailingZeros(rhs2);
            else               shf = 192+Long.numberOfTrailingZeros(rhs3);
            return shl(shf,lhs0,lhs1,lhs2,lhs3);
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

    // Unsigned divide
    private ExceptionalHaltReason div() {
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
            int shf;
            if( rbc0==1 )      shf =     Long.numberOfTrailingZeros(rhs0);
            else if( rbc1==1 ) shf =  64+Long.numberOfTrailingZeros(rhs1);
            else if( rbc2==1 ) shf = 128+Long.numberOfTrailingZeros(rhs2);
            else               shf = 192+Long.numberOfTrailingZeros(rhs3);
            return shr(shf,lhs0,lhs1,lhs2,lhs3);
        }

        throw new TODO();
    }

    private ExceptionalHaltReason sdiv() {
        return ExceptionalHaltReason.INVALID_OPERATION;
    }

    private ExceptionalHaltReason mod() {
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        return ExceptionalHaltReason.INVALID_OPERATION;
    }
    private ExceptionalHaltReason smod() {
        return ExceptionalHaltReason.INVALID_OPERATION;
    }
    private ExceptionalHaltReason addmod() {
        return ExceptionalHaltReason.INVALID_OPERATION;
    }
    private ExceptionalHaltReason mulmod() {
        return ExceptionalHaltReason.INVALID_OPERATION;
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
        // Prolly BigInteger
        throw new TODO();
    }

    // Sgn extend
    private ExceptionalHaltReason sign() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int x = popInt();
        var halt = useGas(_gasCalc.getLowTierGasCost());
        if( halt!=null ) return halt;
        // Push the sign-extend of vak, starting from byte x.  if x>=32, then v
        // is used no-change.  If x==31 then we would only extend the high byte.
        if( x >= 31 ) return null;
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        x = 32 - x;             // Shift byte to high position
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
        long lhs0 = STK0[--_sp], lhs1 = STK1[_sp], lhs2 = STK2[_sp], lhs3 = STK3[_sp];
        long rhs0 = STK0[--_sp], rhs1 = STK1[_sp], rhs2 = STK2[_sp], rhs3 = STK3[_sp];
        int          rez = Long.compareUnsigned(lhs3,rhs3);
        if( rez==0 ) rez = Long.compareUnsigned(lhs2,rhs2);
        if( rez==0 ) rez = Long.compareUnsigned(lhs1,rhs1);
        if( rez==0 ) rez = Long.compareUnsigned(lhs0,rhs0);
        return rez;
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

    // not, bitwise complement (as opposed to a logical not)
    private ExceptionalHaltReason not() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
        if( halt!=null ) return halt;
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long not0 = STK0[--_sp], not1 = STK1[_sp], not2 = STK2[_sp], not3 = STK3[_sp];
        return push(~not0, ~not1, ~not2, ~not3);
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
        Bytes keccak = Hash.keccak256(bytes);
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

    // Push passed originator address
    private ExceptionalHaltReason origin() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getOriginatorAddress());
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
        int dst = popInt();
        int src = popInt();
        int len = popInt();
        var halt = useGas(copyCost(dst,len,3));
        if( halt!=null ) return halt;
        _mem.write(dst, _frame.getInputData(), src, len );
        return null;
    }

    // Push size of code
    private ExceptionalHaltReason codeSize() {
        var halt = useGas(_gasCalc.getVeryLowTierGasCost());
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
        boolean isWarm = _frame.warmUpAddress(address) || _gasCalc.isPrecompile(address);
        long gas = _gasCalc.getExtCodeSizeOperationGasCost() +
            (isWarm ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost());
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
        boolean isWarm = _frame.warmUpAddress(address) || _gasCalc.isPrecompile(address);
        long gas = copyCost(doff,len,0) +
            (isWarm ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost());
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
        boolean isWarm = _frame.warmUpAddress(address) || _gasCalc.isPrecompile(address);
        long gas = _gasCalc.getExtCodeSizeOperationGasCost() +
            (isWarm ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdAccountAccessCost());
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

        _frame.setOutputData(_mem.asBytes(off, len)); // Thin wrapper over the underlying byte[], no copy
        _frame.setState(MessageFrame.State.CODE_SUCCESS);
        // Halt interpreter with no error
        return ExceptionalHaltReason.NONE;
    }

    // Custom Delegate Call 6->1
    private ExceptionalHaltReason customDelegateCall(SB trace, String str) {
        if( FrameUtils.isHookExecution(_frame) && isRedirectFromNativeEntity() )
            return ExceptionalHaltReason.INVALID_OPERATION;

        // BasicCustomCall.executeChecked
        if( _sp < 6 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long stipend = popLong();
        Address to = popAddress();
        int srcOff = popInt();
        int srcLen = popInt();
        int dstOff = popInt();
        int dstLen = popInt();

        var sender = _frame.getSenderAddress();

        return _abstractCall(trace,str, stipend,to,sender,Wei.ZERO,false,srcOff,srcLen,dstOff,dstLen);
    }
    private boolean isRedirectFromNativeEntity() {
        final var updater = (ProxyWorldUpdater) _frame.getWorldUpdater();
        final var recipient = updater.getHederaAccount(_frame.getRecipientAddress());
        return recipient.isTokenFacade() || recipient.isScheduleTxnFacade() || recipient.isRegularAccount();
    }

    // Custom Delegate Call 6->1
    private ExceptionalHaltReason customStaticCall(SB trace, String str) {
        if( FrameUtils.isHookExecution(_frame) && isRedirectFromNativeEntity() )
            return ExceptionalHaltReason.INVALID_OPERATION;

        // BasicCustomCall.executeChecked
        if( _sp < 6 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long stipend = popLong();
        Address to = popAddress();
        int srcOff = popInt();
        int srcLen = popInt();
        int dstOff = popInt();
        int dstLen = popInt();
        return _abstractCall(trace,str, stipend,to,Wei.ZERO,false,srcOff,srcLen,dstOff,dstLen);
    }

    // Revert transaction
    private ExceptionalHaltReason revert() {
        if( _sp < 2 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        int off = popInt();
        int len = popInt();

        var halt = useGas(memoryExpansionGasCost(off, len));
        if( halt!=null ) return halt;

        Bytes reason = _mem.asBytes(off, len); // Thin wrapper over the underlying byte[], no copy
        _frame.setOutputData(reason);
        _frame.setRevertReason(reason);
        _frame.setState(MessageFrame.State.REVERT);
        return ExceptionalHaltReason.NONE;
    }

    private ExceptionalHaltReason customSelfDestruct() {
        if( _sp < 1 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        Operation.OperationResult opr = _selfDestruct.execute(_frame,_bevm, popAddress());
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
        return dst < 0 || dst >= _codes.length || !jumpValid((int)dst)
            ? -1                // Error
            : (int)dst;         // Target
    }

    private int jump() {
        if( _sp < 1 ) return -3;
        if( useGas(_gasCalc.getMidTierGasCost())!=null ) return -2;
        long dst = popLong();
        return dst < 0 || dst >= _codes.length || !jumpValid((int)dst)
            ? -1                // Error
            : (int)dst;         // Target
    }

    // Must jump to a jump dest, opcode 91/0x5B
    private BitSet _jmpDest;
    private boolean jumpValid( int x ) {
        if( _jmpDest == null ) {
            _jmpDest = new BitSet();
            for( int i=0; i<_codes.length; i++ ) {
                int op = _codes[i] & 0xFF;
                if( op == 0x5B ) _jmpDest.set(i); // Set Jump Destination opcodes
                if( op >= 0x60 && op < 0x80 )
                    i += op - 0x60 + 1; // Skip immediate bytes
            }
        }
        return _jmpDest.get(x);
    }

    private ExceptionalHaltReason noop() {
        var halt = useGas(_gasCalc.getJumpDestOperationGasCost());
        if( halt!=null ) return halt;
        return null;
    }

    private ExceptionalHaltReason gas() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_gas);
    }

    private ExceptionalHaltReason gasPrice() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push((UInt256)_frame.getGasPrice().toBytes());
    }

    private ExceptionalHaltReason coinBase() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getMiningBeneficiary() );
    }

    private ExceptionalHaltReason PRNGSeed() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        final Bytes entropy = FrameUtils.proxyUpdaterFor(_frame).entropy();
        return push32(entropy.toArrayUnsafe());
    }

    private ExceptionalHaltReason gasLimit() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        return push(_frame.getBlockValues().getGasLimit());
    }

    private ExceptionalHaltReason customChainId() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        int chainIdAsInt = FrameUtils.configOf(_frame).getConfigData( ContractsConfig.class).chainId();
        return push(chainIdAsInt);
    }

    private ExceptionalHaltReason baseFee() {
        var halt = useGas(_gasCalc.getBaseTierGasCost());
        if( halt!=null ) return halt;
        final Optional<Wei> maybeBaseFee = _frame.getBlockValues().getBaseFee();
        if( maybeBaseFee.isEmpty() )
            return ExceptionalHaltReason.INVALID_OPERATION;
        return push((UInt256)maybeBaseFee.orElseThrow().toBytes());
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

    // All arguments are in bytes
    long memoryExpansionGasCost(int adr, int len) {
        assert adr >=0 && len >= 0;  // Caller already checked
        if( adr+len < 0 ) return Integer.MAX_VALUE; // Overflow gas cost
        if( adr+len < _mem._len ) return 0;         // No extension, so no memory cost
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
        return 3*nwords+base+ memoryExpansionGasCost(off, len);
    }

    // ---------------------
    // Permanent Storage ops

    // Is address+key warmed up?  Used for gas calcs
    private final ArrayList<AdrKey> _freeAK = new ArrayList<>();
    private final HashMap<AdrKey,AdrKey> _internAK = new HashMap<>();
    private static class AdrKey {
        Address _adr;
        UInt256 _ui256; // Warm-up flag; also used to access Account.getStorageValue
        boolean _warm;
        boolean isWarm() {
            if( _warm ) return true;
            _warm = true;
            return false;       // Was cold, but warmed-up afterwards
        }
        @Override public int hashCode() { return _adr.hashCode() ^ _ui256.hashCode(); }
        @Override public boolean equals( Object o ) {
            if( !(o instanceof AdrKey ak) ) return false;
            return _adr.equals(ak._adr) && _ui256==ak._ui256;
        }
    }
    // Get and intern a slot based on Address and key
    AdrKey getSlot( Address adr, long key0, long key1, long key2, long key3) {
        return getSlot(adr, UI256.intern(key0,key1,key2,key3));
    }
    AdrKey getSlot( Address adr, UInt256 key) {
        AdrKey ak = _freeAK.isEmpty() ? new AdrKey() : _freeAK.removeLast();
        ak._adr = adr;
        ak._ui256 = key;
        AdrKey ak2 = _internAK.get(ak);
        if( ak2 != null ) _freeAK.add(ak);
        else              _internAK.put((ak2=ak),ak);
        return ak2;
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
            (ak.isWarm() ? _gasCalc.getWarmStorageReadCost() : _gasCalc.getColdSloadCost());
        var halt = useGas(gas);
        if( halt!=null ) return halt;

        // UInt256 already in AdrKey
        return push( _recv.getStorageValue( ak._ui256 ) );
    }

    private ExceptionalHaltReason customSLoad() {
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
            (ak.isWarm() ? 0 : _gasCalc.getColdSloadCost());
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

    private ExceptionalHaltReason customCreate(SB trace, String str) {
        // Nested create contract call; so print the post-trace before the
        // nested call, and reload the pre-trace state after call.
        if( trace != null )
            System.out.println(postTrace(trace).nl().nl().p("CONTRACT ").p(str).nl());
        if( _sp < 3 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        if( _frame.isStatic() ) return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        Wei value = Wei.wrap(UI256.intern(val0,val1,val2,val3));
        // Memory size for code and gas
        int off = popInt();
        int len = popInt();
        int dstOff = 0;
        int dstLen = 0;

        var sender = _frame.getRecipientAddress().equals(HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS)
            ? FrameUtils.hookOwnerAddress(_frame)
            : _frame.getRecipientAddress();
        ProxyWorldUpdater updater = (ProxyWorldUpdater) _frame.getWorldUpdater();
        var senderAccount = updater.getAccount(sender);

        _frame.clearReturnData();

        if( value.compareTo(senderAccount.getBalance()) > 0 || _frame.getDepth() >= 1024/*AbstractCustomCreateOperation.MAX_STACK_DEPTH*/)
            return push0();

        // since the sender address should be the hook owner for HTS hook executions,
        // we need to explicitly pass in the sender and not use sender.getAddress()
        senderAccount.incrementNonce();
        Code code = _bevm.getCodeUncached(_mem.asBytes(off,len));

        Bytes src = Bytes.EMPTY;

        long senderNonce = senderAccount.getNonce();
        // Decrement nonce by 1 to normalize the effect of transaction execution
        Address contract = Address.contractAddress(sender, senderNonce - 1);
        assert contract != null;

        updater.setupInternalAliasedCreate(sender, contract);
        _frame.warmUpAddress(contract);

        // gas cost for making the contract
        long gas = _gasCalc.txCreateCost() + // 32000
            _gasCalc.initcodeCost(len) + // Shanghai: words(544)*2 == 16 words *2 = 32
            memoryExpansionGasCost(off,len); // 63-63==0
        var halt = useGas(gas);
        if( halt != null ) return null;
        // Gas for child
        long childGasStipend = _gasCalc.gasAvailableForChildCreate(_gas);
        _gas -= childGasStipend;
        _frame.setGasRemaining(_gas);

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
        assert child.getState() == MessageFrame.State.CODE_EXECUTING;

        // ----------------------------
        // Recursively call
        _bevm.runToHalt(child,_tracing);
        // ----------------------------

        switch( child.getState() ) {
        case MessageFrame.State.CODE_SUSPENDED:    throw new TODO("Should not reach here");
        case MessageFrame.State.CODE_SUCCESS:      msg.codeSuccess(child, _tracing);  break; // Sets COMPLETED_SUCCESS
        case MessageFrame.State.EXCEPTIONAL_HALT:  throw new TODO(); // msg.exceptionalHalt(child); break;
        case MessageFrame.State.REVERT:            msg.revert(child);  break;
        case MessageFrame.State.COMPLETED_SUCCESS: throw new TODO("cant find who sets this");
        case MessageFrame.State.COMPLETED_FAILED:  throw new TODO("cant find who sets this");
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
        return push(child.getState() == MessageFrame.State.COMPLETED_SUCCESS ? child.getContractAddress() : null);
    }

    public void complete( MessageFrame frame, MessageFrame child ) {/*nothing*/}

    private ExceptionalHaltReason customCreate2(SB trace, String str) {
        throw new TODO();
    }

    // CustomCallOperation
    private ExceptionalHaltReason customCall(SB trace, String str) {
        // Nested create contract call; so print the post-trace before the
        // nested call, and reload the pre-trace state after call.
        if( trace != null )
            System.out.println(postTrace(trace).nl().nl().p("CONTRACT ").p(str).nl());
        if( _sp < 7 ) return ExceptionalHaltReason.INSUFFICIENT_STACK_ITEMS;
        long stipend = popLong();
        Address to = popAddress();
        long val0 = STK0[--_sp], val1 = STK1[_sp], val2 = STK2[_sp], val3 = STK3[_sp];
        Wei value = Wei.wrap(UI256.intern(val0,val1,val2,val3));
        boolean hasValue = (val0 | val1 | val2 | val3) != 0;
        int srcOff = popInt();
        int srcLen = popInt();
        int dstOff = popInt();
        int dstLen = popInt();
        return _abstractCall(trace,str, stipend,to,value,hasValue,srcOff,srcLen,dstOff,dstLen);
    }


    private ExceptionalHaltReason _abstractCall(SB trace, String str,long stipend, Address contract, Wei value, boolean hasValue, int srcOff, int srcLen, int dstOff, int dstLen ) {
        var sender = _frame.getRecipientAddress().equals(HtsSystemContract.HTS_HOOKS_CONTRACT_ADDRESS)
            ? FrameUtils.hookOwnerAddress(_frame)
            : _frame.getRecipientAddress();
        return _abstractCall(trace, str, stipend, contract, sender, value, hasValue, srcOff, srcLen, dstOff, dstLen );
    }

    private ExceptionalHaltReason _abstractCall(SB trace, String str,long stipend, Address contract, Address sender, Wei value, boolean hasValue, int srcOff, int srcLen, int dstOff, int dstLen ) {
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
        // Check the cold account cost, but do not charge
        if( _gas < gas+_gasCalc.getColdAccountAccessCost() )
            return ExceptionalHaltReason.INSUFFICIENT_GAS;

        if( mustBePresent(contract, hasValue) ) {
        //    FrameUtils.invalidAddressContext(_frame).set(to, InvalidAddressContext.InvalidAddressType.InvalidCallTarget);
        //    return new OperationResult(cost, INVALID_SOLIDITY_ADDRESS);
            throw new TODO();
        }

        // CallOperation
        if( _frame.isStatic() && hasValue )
            return ExceptionalHaltReason.ILLEGAL_STATE_CHANGE;

        // AbstractCallOperation

        // There is a 2nd gas check made here, with account possibly being warm
        // which is lower cost, so never fails...
        gas += _frame.warmUpAddress(contract) || _gasCalc.isPrecompile(contract)
            ? _gasCalc.getWarmStorageReadCost()
            : _gasCalc.getColdAccountAccessCost();
        // Charge gas for call
        var halt = useGas(gas);
        assert halt==null;      // always works because codeAccount check above is larger

        _frame.clearReturnData();

        // Not sure how this can be set
        if( contractAccount!=null && contractAccount.hasDelegatedCode() )
            throw new TODO();

        // If the call is sending more value than the account has or the
        // message frame is to deep return a failed call
        if( value.compareTo(_recv.getBalance()) > 0 || _frame.getDepth() >= 1024 )
            // Unwind gas costs, minus child attempt, push LEGACY_FAILURE_STACK_ITEM and null return
            throw new TODO();
        Bytes src = _mem.asBytes(srcOff, srcLen);

        Code code = CodeV0.EMPTY_CODE;
        // Pre-compiled system contracts have no code
        HederaSystemContract hsys = _bevm._call instanceof CustomMessageCallProcessor cmcp ? cmcp.systemContractsRead(contract) : null;
        if( hsys == null ) {
            // No account, so child frame always returns success
            if( contractAccount==null )
                return push(1);

            // GetCode
            code = _bevm.getCode(contractAccount.getCodeHash(), contractAccount.getCode());
            if( !code.isValid() )
                throw new TODO();
            // No code, so child frame always returns success
            if( code.getSize() == 0 )
                return push(1);
        }

        // gasAvailableForChildCall; this is the "all but 1/64th" computation
        long childGasStipend =  Math.min(_gas - (_gas>>6),stipend);
        _gas -= childGasStipend;
        _frame.setGasRemaining(_gas);

        // ----------------------------
        // child frame is added to frame stack via build method
        MessageFrame child = MessageFrame.builder()
            .parentMessageFrame(_frame)
            .type(MessageFrame.Type.MESSAGE_CALL)
            .initialGas(childGasStipend)
            .address(contract)
            .contract(contract)
            .inputData(src)
            .sender(sender)
            .value(value)
            .apparentValue(value)
            .code(code)
            .isStatic(_frame.isStatic())
            .completer( child0 -> {} )
            .build();
        _frame.setState(MessageFrame.State.CODE_SUSPENDED);

        // Frame lifetime managementQ
        assert child.getState() == MessageFrame.State.NOT_STARTED;
        _tracing.traceContextEnter(child);
        PublicMessageCallProcessor msg = _bevm._call;
        msg.start(child, _tracing);

        if( hsys != null ) {
            //assert child.getState() == MessageFrame.State.COMPLETED_SUCCESS;
        } else {
            assert child.getState() == MessageFrame.State.CODE_EXECUTING;
            // ----------------------------
            // Recursively call
            _bevm.runToHalt(child,_tracing);
            // ----------------------------
        }

        switch( child.getState() ) {
        case MessageFrame.State.CODE_SUSPENDED:    throw new TODO("Should not reach here");
        case MessageFrame.State.CODE_SUCCESS:      msg.codeSuccess(child, _tracing);  break; // Sets COMPLETED_SUCCESS
        case MessageFrame.State.EXCEPTIONAL_HALT:  FrameUtils.exceptionalHalt(child); break;
        case MessageFrame.State.REVERT:            msg.revert(child);  break;
        case MessageFrame.State.COMPLETED_SUCCESS: break; // Precompiled sys contracts hit here
        case MessageFrame.State.COMPLETED_FAILED:  throw new TODO("cant find who sets this");
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

        _gas += child.getRemainingGas();

        if( trace != null )
            System.out.println(trace.clear().p("RETURN ").p(str).nl());
        return push(child.getState()==MessageFrame.State.COMPLETED_SUCCESS ? 1 : 0);
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
