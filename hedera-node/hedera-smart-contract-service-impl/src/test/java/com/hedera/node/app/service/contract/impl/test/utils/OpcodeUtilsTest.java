// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.utils;

import static com.hedera.node.app.service.contract.impl.utils.OpcodeUtils.asCallOperationType;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.streams.CallOperationType;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpcodeUtilsTest {
    @ParameterizedTest
    @CsvSource({
        "0xF0,OP_CREATE",
        "0xF1,OP_CALL",
        "0xF2,OP_CALLCODE",
        "0xF4,OP_DELEGATECALL",
        "0xF5,OP_CREATE2",
        "0xFA,OP_STATICCALL",
        "0x12,OP_UNKNOWN",
    })
    void convertsOpcodesToCallOpsAsExpected(final int opCode, @NonNull final CallOperationType type) {
        assertEquals(type, asCallOperationType(opCode));
    }
}
