// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculatorImpl;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;

class HederaGasCalculatorImplTest {
    private final HederaGasCalculatorImpl subject = new HederaGasCalculatorImpl();

    @Test
    void txnIntrinsicCostContractCreate() {
        assertEquals(
                21_000L + // base TX cost
                        32_000L, // contract creation base cost
                subject.transactionGasRequirements(Bytes.EMPTY, true, 0L).intrinsicGas());
    }

    @Test
    void txnIntrinsicCostNonContractCreate() {
        assertEquals(
                21_000L, // base TX cost
                subject.transactionGasRequirements(Bytes.EMPTY, false, 0L).intrinsicGas());
    }

    @Test
    void codeDepositCostIsUsingFrontierGasCost() {
        assertEquals(200 * 1000, subject.codeDepositGasCost(1000));
    }

    @Test
    void transactionIntrinsicGasCost() {
        assertEquals(
                4 * 2 + // zero byte cost
                        16 * 3 + // non-zero byte cost
                        21_000L, // base TX cost
                subject.transactionGasRequirements(Bytes.of(0, 1, 2, 3, 0), false, 0L)
                        .intrinsicGas());
        assertEquals(
                4 * 3 + // zero byte cost
                        16 * 2 + // non-zero byte cost
                        21_000L + // base TX cost
                        32_000L, // contract creation base cost
                subject.transactionGasRequirements(Bytes.of(0, 1, 0, 3, 0), true, 0L)
                        .intrinsicGas());
    }
}
