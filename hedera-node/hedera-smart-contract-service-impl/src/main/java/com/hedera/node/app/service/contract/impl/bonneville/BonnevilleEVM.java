// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.processors.*;
import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import com.hedera.node.app.service.contract.impl.utils.TODO;

import edu.umd.cs.findbugs.annotations.NonNull;

import java.math.BigInteger;
import java.util.*;

// BESU imports
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.tracing.OperationTracer;

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
        preComputeGasTables();
    }


    // -----------------------------------------------------------
    // Hot-loop prechecks (stack + fixed-tier gas)
    //
    // Many opcodes have a very regular prologue:
    //   - check stack depth
    //   - charge a fixed tier gas amount
    //
    // We move that into the interpreter loop using static tables indexed by opcode.
    // Complex/dynamic ops (memory expansion, warm/cold access, EXP, LOG, CALL-family, etc.)
    // "escape" by using GAS_DYNAMIC and keep doing their own gas logic.
    final byte[] _opStackMin = new byte[256];
    final byte[] _opGas = new byte[256];

    static final byte GAS_DYNAMIC = -1;
    private static final byte GAS_NONE    = 0;
    private static final byte GAS_VERYLOW = 1;
    private static final byte GAS_LOW     = 2;
    private static final byte GAS_MID     = 3;
    private static final byte GAS_HIGH    = 4;
    private static final byte GAS_BASE    = 5;
    private static final byte GAS_JUMPDEST= 6;
    private void preComputeGasTables() {
        // Default: dynamic (method will do checks)
        Arrays.fill(_opGas, GAS_DYNAMIC);

        // --- 0x00 STOP
        _opStackMin[0x00] = 0; _opGas[0x00] = GAS_NONE;

        // --- Arithmetic / bitwise: fixed tiers, regular stack usage
        _opStackMin[0x01] = 2; _opGas[0x01] = GAS_VERYLOW; // ADD
        _opStackMin[0x02] = 2; _opGas[0x02] = GAS_LOW;     // MUL
        _opStackMin[0x03] = 2; _opGas[0x03] = GAS_VERYLOW; // SUB
        _opStackMin[0x04] = 2; _opGas[0x04] = GAS_LOW;     // DIV
        _opStackMin[0x05] = 2; _opGas[0x05] = GAS_LOW;     // SDIV
        _opStackMin[0x06] = 2; _opGas[0x06] = GAS_LOW;     // MOD
        _opStackMin[0x07] = 2; _opGas[0x07] = GAS_LOW;     // SMOD
        _opStackMin[0x08] = 3; _opGas[0x08] = GAS_MID;     // ADDMOD
        _opStackMin[0x09] = 3; _opGas[0x09] = GAS_MID;     // MULMOD
        _opStackMin[0x0A] = 2; _opGas[0x0A] = GAS_DYNAMIC; // EXP (dynamic)
        _opStackMin[0x0B] = 2; _opGas[0x0B] = GAS_VERYLOW; // SIGN

        _opStackMin[0x10] = 2; _opGas[0x10] = GAS_VERYLOW; // LT
        _opStackMin[0x11] = 2; _opGas[0x11] = GAS_VERYLOW; // GT
        _opStackMin[0x12] = 2; _opGas[0x12] = GAS_VERYLOW; // SLT
        _opStackMin[0x13] = 2; _opGas[0x13] = GAS_VERYLOW; // SGT
        _opStackMin[0x14] = 2; _opGas[0x14] = GAS_VERYLOW; // EQ
        _opStackMin[0x15] = 1; _opGas[0x15] = GAS_VERYLOW; // ISZERO
        _opStackMin[0x16] = 2; _opGas[0x16] = GAS_VERYLOW; // AND
        _opStackMin[0x17] = 2; _opGas[0x17] = GAS_VERYLOW; // OR
        _opStackMin[0x18] = 2; _opGas[0x18] = GAS_VERYLOW; // XOR
        _opStackMin[0x19] = 1; _opGas[0x19] = GAS_VERYLOW; // NOT
        _opStackMin[0x1A] = 2; _opGas[0x1A] = GAS_VERYLOW; // BYTE
        _opStackMin[0x1B] = 2; _opGas[0x1B] = GAS_VERYLOW; // SHL
        _opStackMin[0x1C] = 2; _opGas[0x1C] = GAS_VERYLOW; // SHR
        _opStackMin[0x1D] = 2; _opGas[0x1D] = GAS_VERYLOW; // SAR

        // --- 0x20 KECCAK256 is dynamic (memory expansion)
        _opStackMin[0x20] = 2; _opGas[0x20] = GAS_DYNAMIC;

        // --- 0x30...0x4A: mostly dynamic due to warm/cold, external reads, etc.
        _opStackMin[0x30] = 0; _opGas[0x30] = GAS_BASE;    // ADDRESS
        _opStackMin[0x31] = 1; _opGas[0x31] = GAS_DYNAMIC; // BALANCE
        _opStackMin[0x32] = 0; _opGas[0x32] = GAS_BASE;    // ORIGIN
        _opStackMin[0x33] = 0; _opGas[0x33] = GAS_BASE;    // CALLER
        _opStackMin[0x34] = 0; _opGas[0x34] = GAS_BASE;    // CALLVALUE
        _opStackMin[0x35] = 1; _opGas[0x35] = GAS_VERYLOW; // CALLDATALOAD
        _opStackMin[0x36] = 0; _opGas[0x36] = GAS_BASE;    // CALLDATASIZE
        _opStackMin[0x37] = 3; _opGas[0x37] = GAS_DYNAMIC; // CALLDATALOAD
        _opStackMin[0x38] = 0; _opGas[0x38] = GAS_BASE;    // CODESIZE
        _opStackMin[0x39] = 3; _opGas[0x38] = GAS_DYNAMIC; // CODECOPY
        _opStackMin[0x3A] = 0; _opGas[0x3A] = GAS_BASE;    // GASPRICE
        _opStackMin[0x3C] = 4; _opGas[0x3C] = GAS_DYNAMIC; // CUSTOMEXTCODECOPY
        _opStackMin[0x3D] = 0; _opGas[0x3D] = GAS_BASE;    // RETURNDATASIZSE
        _opStackMin[0x3E] = 3; _opGas[0x3E] = GAS_DYNAMIC; // RETURNDATACOPY
        _opStackMin[0x3F] = 1; _opGas[0x3F] = GAS_DYNAMIC; // CUSTOMEXTCODEHASH
        _opStackMin[0x40] = 2; _opGas[0x40] = GAS_DYNAMIC; // BLOCKHASH
        _opStackMin[0x41] = 0; _opGas[0x41] = GAS_BASE;    // COINBASE
        _opStackMin[0x42] = 0; _opGas[0x42] = GAS_BASE;    // TIMESTAMP
        _opStackMin[0x43] = 0; _opGas[0x43] = GAS_BASE;    // NUMBER
        _opStackMin[0x44] = 0; _opGas[0x44] = GAS_BASE;    // PRNGSEED
        _opStackMin[0x45] = 0; _opGas[0x45] = GAS_BASE;    // GASLIMIT
        _opStackMin[0x46] = 0; _opGas[0x46] = GAS_BASE;    // CHAINID (custom)
        _opStackMin[0x47] = 0; _opGas[0x47] = GAS_LOW;     // SELFBALANCE
        _opStackMin[0x48] = 0; _opGas[0x48] = GAS_BASE;    // BASEFEE
        _opStackMin[0x49] = 1; _opGas[0x49] = GAS_VERYLOW; // BLOBHASH
        _opStackMin[0x4A] = 0; _opGas[0x4A] = GAS_BASE;    // BLOBBASEFEE

        // --- 0x50...stack/memory helpers
        _opStackMin[0x50] = 1; _opGas[0x50] = GAS_BASE;    // POP
        _opStackMin[0x51] = 1; _opGas[0x51] = GAS_DYNAMIC; // MLOAD
        _opStackMin[0x52] = 2; _opGas[0x52] = GAS_DYNAMIC; // MSTORE
        _opStackMin[0x53] = 2; _opGas[0x53] = GAS_DYNAMIC; // MSTORE8
        _opStackMin[0x54] = 1; _opGas[0x54] = GAS_DYNAMIC; // CUSTOMSLOAD
        _opStackMin[0x55] = 2; _opGas[0x55] = GAS_DYNAMIC; // CUSTOMSSTORE
        _opStackMin[0x56] = 1; _opGas[0x56] = GAS_MID;     // JUMP (keeps its own special return codes)
        _opStackMin[0x57] = 2; _opGas[0x57] = GAS_HIGH;    // JUMPI
        _opStackMin[0x58] = 0; _opGas[0x58] = GAS_BASE;    // PC
        _opStackMin[0x59] = 0; _opGas[0x59] = GAS_BASE;    // MSIZE
        _opStackMin[0x5A] = 0; _opGas[0x5A] = GAS_BASE;    // GAS
        _opStackMin[0x5B] = 0; _opGas[0x5B] = GAS_JUMPDEST;// JUMPDEST
        _opStackMin[0x5D] = 2; _opGas[0x5D] = GAS_DYNAMIC; // TMPSTORE
        _opStackMin[0x5E] = 3; _opGas[0x5E] = GAS_DYNAMIC; // MCOPY
        _opStackMin[0x5F] = 0; _opGas[0x5F] = GAS_BASE;    // PUSH0

        // PUSH1...PUSH32: 0 stack required, fixed very low tier
        for (int op = 0x60; op <= 0x7F; op++) {
            _opStackMin[op] = 0;
            _opGas[op] = GAS_VERYLOW;
        }
        // DUP1...DUP16: require N, cost very-low
        for (int op = 0x80; op <= 0x8F; op++) {
            _opStackMin[op] = (byte) (op - 0x80 + 1);
            _opGas[op] = GAS_VERYLOW;
        }
        // SWAP1...SWAP16: require N+1, cost very-low
        for (int op = 0x90; op <= 0x9F; op++) {
            _opStackMin[op] = (byte) (op - 0x90 + 2);
            _opGas[op] = GAS_VERYLOW;
        }

        // LOG0...LOG4 are dynamic (memory expansion + topics loop)
        for (int op = 0xA0; op <= 0xA4; op++) {
            _opStackMin[op] = (byte) (op - 0xA0 + 2); // topics are checked again in method; this is a cheap early reject
            _opGas[op] = GAS_DYNAMIC;
        }

        // RET/REVERT are dynamic (memory expansion)
        _opStackMin[0xF3] = 2; _opGas[0xF3] = GAS_DYNAMIC;
        _opStackMin[0xFD] = 2; _opGas[0xFD] = GAS_DYNAMIC;

        // SELFDESTRUCT has fixed-ish core gas but also cold/warm beneficiary/etc here; keep dynamic
        _opStackMin[0xFF] = 1; _opGas[0xFF] = GAS_DYNAMIC;

        // Fill in with real gas, depending on the gas calculator used
        var gasCalc = getGasCalculator();
        long veryLow  = gasCalc.getVeryLowTierGasCost();
        long low      = gasCalc.getLowTierGasCost();
        long mid      = gasCalc.getMidTierGasCost();
        long high     = gasCalc.getHighTierGasCost();
        long base     = gasCalc.getBaseTierGasCost();
        long jumpDest = gasCalc.getJumpDestOperationGasCost();

        for( int op = 0; op < 256; op++ ) {
            _opGas[op] = switch( _opGas[op] ) {
            case GAS_NONE     -> (byte)0L;
            case GAS_VERYLOW  -> (byte)veryLow;
            case GAS_LOW      -> (byte)low;
            case GAS_MID      -> (byte)mid;
            case GAS_HIGH     -> (byte)high;
            case GAS_BASE     -> (byte)base;
            case GAS_JUMPDEST -> (byte)jumpDest;
            default           -> GAS_DYNAMIC; // dynamic / escape
            };
        }
    }


    // ---------------------
    // Shared free-list of BEVMs, to avoid making new with every contract.
    // IMPORTANT: nested calls require a BEVM per call, but pooling can still reuse instances.
    private static final int MAX_FREE_PER_THREAD = 64;
    private static final ThreadLocal<ArrayDeque<BEVM>> FREE = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public void runToHalt(MessageFrame frame, OperationTracer tracing) {
        CodeV2 code = CodeV2.make(frame.getCode().getBytes().toArrayUnsafe());
        if( code!=null && frame.getCode()==null )
            throw new TODO("Failed validation");
        runToHalt(null, code, frame, tracing);
    }

    void runToHalt(BEVM parent, CodeV2 code, MessageFrame frame, OperationTracer tracing ) {
        final ArrayDeque<BEVM> frees = FREE.get();
        final BEVM free = frees.pollLast();
        final BEVM bevm = (free == null)
                ? new BEVM(this, getOperationsUnsafe())
                : free;

        // Run the contract bytecodes
        bevm.init(parent, code, frame, tracing).run(parent == null).reset(parent == null);

        if( frees.size() < MAX_FREE_PER_THREAD )
            frees.addLast(bevm);
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

    // Used in exp()
    static final BigInteger MOD_BASE = BigInteger.TWO.pow(256);

}
