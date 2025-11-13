// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculatorImpl;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
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
                        32_000L + // contract creation base cost
                        2, // contract creation 1 word cost
                subject.transactionGasRequirements(Bytes.of(0, 1, 0, 3, 0), true, 0L)
                        .intrinsicGas());
    }

    @Test
    void transactionGasRequirements() {
        final var payloadLength = 2048;
        byte[] randomPayload = new byte[payloadLength];
        ThreadLocalRandom.current().nextBytes(randomPayload);
        final var zeros = IntStream.range(0, randomPayload.length)
                .filter(idx -> randomPayload[idx] == 0)
                .count();
        // regular transaction
        final var gasRequirements = subject.transactionGasRequirements(Bytes.of(randomPayload), false, 0L);
        assertNotEquals(gasRequirements.intrinsicGas(), gasRequirements.minimumGasUsed());
        // gasUsed defined at https://eips.ethereum.org/EIPS/eip-7623
        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST
                        + HederaGasCalculatorImpl.TX_DATA_ZERO_COST * zeros
                        + HederaGasCalculatorImpl.ISTANBUL_TX_DATA_NON_ZERO_COST * (randomPayload.length - zeros),
                gasRequirements.intrinsicGas());
        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST + (zeros + (randomPayload.length - zeros) * 4) * 10,
                gasRequirements.minimumGasUsed());
    }

    @Test
    void transactionGasRequirementsContractCreate() {
        final var payloadLength = 2048;
        byte[] randomPayload = new byte[payloadLength];
        ThreadLocalRandom.current().nextBytes(randomPayload);
        final var zeros = IntStream.range(0, randomPayload.length)
                .filter(idx -> randomPayload[idx] == 0)
                .count();
        // regular transaction
        final var gasRequirements = subject.transactionGasRequirements(Bytes.of(randomPayload), true, 0L);
        assertNotEquals(gasRequirements.intrinsicGas(), gasRequirements.minimumGasUsed());
        // gasUsed defined at https://eips.ethereum.org/EIPS/eip-7623
        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST
                        + HederaGasCalculatorImpl.TX_DATA_ZERO_COST * zeros
                        + HederaGasCalculatorImpl.ISTANBUL_TX_DATA_NON_ZERO_COST * (randomPayload.length - zeros)
                        + 32_000L
                        + (randomPayload.length / 32 * 2),
                gasRequirements.intrinsicGas());
        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST + (zeros + (randomPayload.length - zeros) * 4) * 10,
                gasRequirements.minimumGasUsed());
    }
}
