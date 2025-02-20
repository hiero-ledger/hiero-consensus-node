// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.utils;

import static com.hedera.hapi.streams.CallOperationType.OP_CALL;
import static com.hedera.hapi.streams.CallOperationType.OP_CALLCODE;
import static com.hedera.hapi.streams.CallOperationType.OP_CREATE;
import static com.hedera.hapi.streams.CallOperationType.OP_CREATE2;
import static com.hedera.hapi.streams.CallOperationType.OP_DELEGATECALL;
import static com.hedera.hapi.streams.CallOperationType.OP_STATICCALL;
import static com.hedera.hapi.streams.CallOperationType.OP_UNKNOWN;

import com.hedera.hapi.streams.CallOperationType;

/**
 * Some utilities related to EVM opcode.
 */
public class OpcodeUtils {
    private OpcodeUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    public static final int OP_CODE_STATICCALL = 0xFA;
    public static final int OP_CODE_CREATE = 0xF0;
    public static final int OP_CODE_CALL = 0xF1;
    public static final int OP_CODE_CALLCODE = 0xF2;
    public static final int OP_CODE_DELEGATECALL = 0xF4;
    public static final int OP_CODE_CREATE2 = 0xF5;

    /**
     * Given an opcode, returns the corresponding {@link CallOperationType}.
     *
     * @param opCode the opcode to convert
     * @return the corresponding {@link CallOperationType}
     */
    public static CallOperationType asCallOperationType(final int opCode) {
        return switch (opCode) {
            case OP_CODE_CREATE -> OP_CREATE;
            case OP_CODE_CALL -> OP_CALL;
            case OP_CODE_CALLCODE -> OP_CALLCODE;
            case OP_CODE_DELEGATECALL -> OP_DELEGATECALL;
            case OP_CODE_CREATE2 -> OP_CREATE2;
            case OP_CODE_STATICCALL -> OP_STATICCALL;
            default -> OP_UNKNOWN;
        };
    }
}
