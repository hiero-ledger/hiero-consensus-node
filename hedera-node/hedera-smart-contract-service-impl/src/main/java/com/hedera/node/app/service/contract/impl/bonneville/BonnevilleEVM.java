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
    }

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
