// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import com.hedera.node.app.service.contract.impl.exec.ActionSidecarContentTracer;
import com.hedera.node.app.service.contract.impl.exec.AddressChecks;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.processors.*;
import com.hedera.node.app.service.contract.impl.hevm.HEVM;
import com.hedera.node.app.service.contract.impl.utils.TODO;
import java.math.BigInteger;
import java.util.*;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.frame.MessageFrame;
import org.hyperledger.besu.evm.gascalculator.GasCalculator;
import org.hyperledger.besu.evm.internal.EvmConfiguration;
import org.hyperledger.besu.evm.operation.OperationRegistry;
import org.hyperledger.besu.evm.tracing.OperationTracer;

// spotless:off
public class BonnevilleEVM extends HEVM {
    public static final String TOP_SEP = "======================================================================";

    final FeatureFlags _flags;
    final AddressChecks _adrChk;

    // A singleton BonnevilleEVM is created for all threads.  In *theory* we
    // might have more than one, specialized by e.g. flags or gasCalc.  In
    // practice, I've only ever seen one per JVM.
    public BonnevilleEVM(
            OperationRegistry operations,
            GasCalculator gasCalc,
            EvmConfiguration evmConfiguration,
            EvmSpecVersion evmSpecVersion,
            FeatureFlags featureFlags,
            AddressChecks addressChecks) {
        super(operations, gasCalc, evmConfiguration, evmSpecVersion);
        _flags = featureFlags;
        _adrChk = addressChecks;
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

    private void preComputeGasTables() {

        // Fill in with real gas, depending on the gas calculator used
        var gasCalc = getGasCalculator();
        byte dynamic  = -1;
        byte none     =  0;
        byte veryLow  = checkGas(gasCalc.getVeryLowTierGasCost());
        byte low      = checkGas(gasCalc.getLowTierGasCost());
        byte mid      = checkGas(gasCalc.getMidTierGasCost());
        byte high     = checkGas(gasCalc.getHighTierGasCost());
        byte base     = checkGas(gasCalc.getBaseTierGasCost());
        byte jumpDest = checkGas(gasCalc.getJumpDestOperationGasCost());

        // Default: dynamic (method will do checks)
        Arrays.fill(_opGas, dynamic);

        // --- 0x00 STOP
        _opStackMin[0x00] = 0;  _opGas[0x00] = none;

        // --- Arithmetic / bitwise: fixed tiers, regular stack usage
        _opStackMin[0x01] = 2;  _opGas[0x01] = veryLow; // ADD
        _opStackMin[0x02] = 2;  _opGas[0x02] = low; // MUL
        _opStackMin[0x03] = 2;  _opGas[0x03] = veryLow; // SUB
        _opStackMin[0x04] = 2;  _opGas[0x04] = low; // DIV
        _opStackMin[0x05] = 2;  _opGas[0x05] = low; // SDIV
        _opStackMin[0x06] = 2;  _opGas[0x06] = low; // MOD
        _opStackMin[0x07] = 2;  _opGas[0x07] = low; // SMOD
        _opStackMin[0x08] = 3;  _opGas[0x08] = mid; // ADDMOD
        _opStackMin[0x09] = 3;  _opGas[0x09] = mid; // MULMOD
        _opStackMin[0x0A] = 2;  _opGas[0x0A] = dynamic; // EXP (dynamic)
        _opStackMin[0x0B] = 2;  _opGas[0x0B] = low; // SIGN

        _opStackMin[0x10] = 2;  _opGas[0x10] = veryLow; // LT
        _opStackMin[0x11] = 2;  _opGas[0x11] = veryLow; // GT
        _opStackMin[0x12] = 2;  _opGas[0x12] = veryLow; // SLT
        _opStackMin[0x13] = 2;  _opGas[0x13] = veryLow; // SGT
        _opStackMin[0x14] = 2;  _opGas[0x14] = veryLow; // EQ
        _opStackMin[0x15] = 1;  _opGas[0x15] = veryLow; // ISZERO
        _opStackMin[0x16] = 2;  _opGas[0x16] = veryLow; // AND
        _opStackMin[0x17] = 2;  _opGas[0x17] = veryLow; // OR
        _opStackMin[0x18] = 2;  _opGas[0x18] = veryLow; // XOR
        _opStackMin[0x19] = 1;  _opGas[0x19] = veryLow; // NOT
        _opStackMin[0x1A] = 2;  _opGas[0x1A] = veryLow; // BYTE
        _opStackMin[0x1B] = 2;  _opGas[0x1B] = veryLow; // SHL
        _opStackMin[0x1C] = 2;  _opGas[0x1C] = veryLow; // SHR
        _opStackMin[0x1D] = 2;  _opGas[0x1D] = veryLow; // SAR

        // --- 0x20 KECCAK256 is dynamic (memory expansion)
        _opStackMin[0x20] = 2;  _opGas[0x20] = dynamic;

        // --- 0x30...0x4A: mostly dynamic due to warm/cold, external reads, etc.
        _opStackMin[0x30] = 0;  _opGas[0x30] = base; // ADDRESS
        _opStackMin[0x31] = 1;  _opGas[0x31] = dynamic; // BALANCE
        _opStackMin[0x32] = 0;  _opGas[0x32] = base; // ORIGIN
        _opStackMin[0x33] = 0;  _opGas[0x33] = base; // CALLER
        _opStackMin[0x34] = 0;  _opGas[0x34] = base; // CALLVALUE
        _opStackMin[0x35] = 1;  _opGas[0x35] = veryLow; // CALLDATALOAD
        _opStackMin[0x36] = 0;  _opGas[0x36] = base; // CALLDATASIZE
        _opStackMin[0x37] = 3;  _opGas[0x37] = dynamic; // CALLDATALOAD
        _opStackMin[0x38] = 0;  _opGas[0x38] = base; // CODESIZE
        _opStackMin[0x39] = 3;  _opGas[0x39] = dynamic; // CODECOPY
        _opStackMin[0x3B] = 0;  _opGas[0x3B] = dynamic; // EXTCODESIZE
        _opStackMin[0x3A] = 0;  _opGas[0x3A] = base; // GASPRICE
        _opStackMin[0x3C] = 4;  _opGas[0x3C] = dynamic; // CUSTOMEXTCODECOPY
        _opStackMin[0x3D] = 0;  _opGas[0x3D] = base; // RETURNDATASIZSE
        _opStackMin[0x3E] = 3;  _opGas[0x3E] = dynamic; // RETURNDATACOPY
        _opStackMin[0x3F] = 1;  _opGas[0x3F] = dynamic; // CUSTOMEXTCODEHASH
        _opStackMin[0x40] = 2;  _opGas[0x40] = dynamic; // BLOCKHASH
        _opStackMin[0x41] = 0;  _opGas[0x41] = base; // COINBASE
        _opStackMin[0x42] = 0;  _opGas[0x42] = base; // TIMESTAMP
        _opStackMin[0x43] = 0;  _opGas[0x43] = base; // NUMBER
        _opStackMin[0x44] = 0;  _opGas[0x44] = base; // PRNGSEED
        _opStackMin[0x45] = 0;  _opGas[0x45] = base; // GASLIMIT
        _opStackMin[0x46] = 0;  _opGas[0x46] = base; // CHAINID (custom)
        _opStackMin[0x47] = 0;  _opGas[0x47] = low; // SELFBALANCE
        _opStackMin[0x48] = 0;  _opGas[0x48] = base; // BASEFEE
        _opStackMin[0x49] = 1;  _opGas[0x49] = veryLow; // BLOBHASH
        _opStackMin[0x4A] = 0;  _opGas[0x4A] = base; // BLOBBASEFEE

        // --- 0x50...stack/memory helpers
        _opStackMin[0x50] = 1;  _opGas[0x50] = base; // POP
        _opStackMin[0x51] = 1;  _opGas[0x51] = dynamic; // MLOAD
        _opStackMin[0x52] = 2;  _opGas[0x52] = dynamic; // MSTORE
        _opStackMin[0x53] = 2;  _opGas[0x53] = dynamic; // MSTORE8
        _opStackMin[0x54] = 1;  _opGas[0x54] = dynamic; // CUSTOMSLOAD
        _opStackMin[0x55] = 2;  _opGas[0x55] = dynamic; // CUSTOMSSTORE
        _opStackMin[0x56] = 1;  _opGas[0x56] = mid; // JUMP (keeps its own special return codes)
        _opStackMin[0x57] = 2;  _opGas[0x57] = high; // JUMPI
        _opStackMin[0x58] = 0;  _opGas[0x58] = base; // PC
        _opStackMin[0x59] = 0;  _opGas[0x59] = base; // MSIZE
        _opStackMin[0x5A] = 0;  _opGas[0x5A] = base; // GAS
        _opStackMin[0x5B] = 0;  _opGas[0x5B] = jumpDest; // JUMPDEST
        _opStackMin[0x5D] = 2;  _opGas[0x5D] = dynamic; // TMPSTORE
        _opStackMin[0x5E] = 3;  _opGas[0x5E] = dynamic; // MCOPY
        _opStackMin[0x5F] = 0;  _opGas[0x5F] = base; // PUSH0

        // PUSH1...PUSH32: 0 stack required, fixed very low tier
        for (int op = 0x60; op <= 0x7F; op++) {
            _opStackMin[op] = 0;
            _opGas[op] = veryLow;
        }
        // DUP1...DUP16: require N, cost very-low
        for (int op = 0x80; op <= 0x8F; op++) {
            _opStackMin[op] = (byte) (op - 0x80 + 1);
            _opGas[op] = veryLow;
        }
        // SWAP1...SWAP16: require N+1, cost very-low
        for (int op = 0x90; op <= 0x9F; op++) {
            _opStackMin[op] = (byte) (op - 0x90 + 2);
            _opGas[op] = veryLow;
        }

        // LOG0...LOG4 are dynamic (memory expansion + topics loop)
        for (int op = 0xA0; op <= 0xA4; op++) {
            _opStackMin[op] = (byte) (op - 0xA0 + 2); // topics are checked again in method; this is a cheap early reject
            _opGas[op] = dynamic;
        }

        // RET/REVERT are dynamic (memory expansion)
        _opStackMin[0xF3] = 2;  _opGas[0xF3] = dynamic;
        _opStackMin[0xFD] = 2;  _opGas[0xFD] = dynamic;

        // SELFDESTRUCT has fixed-ish core gas but also cold/warm beneficiary/etc here; keep dynamic
        _opStackMin[0xFF] = 1;  _opGas[0xFF] = dynamic;
    }

    private static byte checkGas(long gas) {
        assert 0 <= gas && gas < 127; // TODO: Widen gas if we find e.g. very large common tier gas
        return (byte) gas;
    }

    // ---------------------

    // Shared free-list of BEVMs, to avoid making new with every contract.
    // IMPORTANT: nested calls require a BEVM per call, but pooling can still
    // reuse instances.

    private static final int MAX_FREE_PER_THREAD = 64;
    private final ThreadLocal<ArrayDeque<BEVM>> FREE = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public void runToHalt(MessageFrame frame, OperationTracer tracer) {
        CodeV2 code = CodeV2.make(frame.getCode().getBytes().toArrayUnsafe());
        if( code != null && frame.getCode() == null )
            throw new TODO("Failed validation");
        if(!(tracer instanceof ActionSidecarContentTracer scTracer) )
            throw new TODO("Only for ASCTracer");
        // Top-level run-to-halt
        runToHalt(null, code, frame, scTracer);
    }

    void runToHalt(BEVM parent, CodeV2 code, MessageFrame frame, ActionSidecarContentTracer tracer) {
        final ArrayDeque<BEVM> frees = FREE.get();
        final BEVM free = frees.pollLast();
        final BEVM bevm = (free == null) ? new BEVM(this, getOperationsUnsafe()) : free;

        // Run the contract bytecodes
        bevm.init(parent, code, frame, tracer).run(parent == null).reset(parent == null);

        // Pop the action stack for not-pre-compiled.
        if( bevm._hasSideCar )
            tracer.traceNotExecuting(frame);

        if( frees.size() < MAX_FREE_PER_THREAD ) frees.addLast(bevm);
    }

    // ---------------------
    public static String OPNAME(int op) {
        if( op <  0x60 ) return OPNAMES[op];
        if( op <  0x80 ) return "psh" + (op - 0x60 + 1);
        if( op <  0x90 ) return "dup" + (op - 0x80 + 1);
        if( op <  0xA0 ) return "swp" + (op - 0x90 + 1);
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
        return String.format("%x", op);
    }

    private static final String[] OPNAMES = new String[] {
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
// spotless:on
