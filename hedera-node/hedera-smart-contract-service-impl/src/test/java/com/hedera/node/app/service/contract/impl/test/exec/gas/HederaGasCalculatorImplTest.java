// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.contract.impl.exec.gas.HederaGasCalculatorImpl;
import com.hedera.node.app.service.contract.impl.test.TestByteUtils;
import com.hedera.node.app.service.contract.impl.test.TestTransactionUtils;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HederaGasCalculatorImplTest {

    private final HederaGasCalculatorImpl subject = new HederaGasCalculatorImpl();

    @Test
    void txnIntrinsicCostContractCreate() {
        assertEquals(
                21_000L + // base TX cost
                        32_000L, // contract creation base cost
                subject.transactionGasRequirements(Bytes.EMPTY, true, null, null)
                        .intrinsicGas());
    }

    @Test
    void txnIntrinsicCostNonContractCreate() {
        assertEquals(
                21_000L, // base TX cost
                subject.transactionGasRequirements(Bytes.EMPTY, false, null, null)
                        .intrinsicGas());
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
                subject.transactionGasRequirements(Bytes.of(0, 1, 2, 3, 0), false, null, null)
                        .intrinsicGas());
        assertEquals(
                4 * 3 + // zero byte cost
                        16 * 2 + // non-zero byte cost
                        21_000L + // base TX cost
                        32_000L + // contract creation base cost
                        2, // contract creation 1 word cost
                subject.transactionGasRequirements(Bytes.of(0, 1, 0, 3, 0), true, null, null)
                        .intrinsicGas());
    }

    // CallData https://eips.ethereum.org/EIPS/eip-7623 -----------------------------
    @Test
    void transactionGasRequirements() {
        final var payloadLength = 2048;
        final var randomPayload = TestByteUtils.randomBytes(payloadLength);
        final var zeros = IntStream.range(0, randomPayload.length)
                .filter(idx -> randomPayload[idx] == 0)
                .count();
        // regular transaction
        final var gasRequirements = subject.transactionGasRequirements(Bytes.of(randomPayload), false, null, null);
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
        final var randomPayload = TestByteUtils.randomBytes(payloadLength);
        final var zeros = IntStream.range(0, randomPayload.length)
                .filter(idx -> randomPayload[idx] == 0)
                .count();
        // regular transaction
        final var gasRequirements = subject.transactionGasRequirements(Bytes.of(randomPayload), true, null, null);
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

    // Verifies the word-at-a-time zero-byte scan matches a naive per-byte count for the public
    // intrinsic gas across word boundaries, tail lengths, and high-bit / 0x01 byte values.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 1000, 2048, 2049})
    void payloadZeroCountMatchesNaiveCountAcrossLengths(final int length) {
        final var payload = new byte[length];
        final var rnd = new Random(length * 31L + 7L);
        for (int i = 0; i < length; i++) {
            // ~40% zeros, otherwise any non-zero value including high-bit (>= 0x80) and 0x01 bytes
            payload[i] = (byte) (rnd.nextInt(10) < 4 ? 0 : (rnd.nextInt(255) + 1));
        }
        long expectedZeros = 0;
        for (final var b : payload) {
            if (b == 0) {
                expectedZeros++;
            }
        }
        final long nonZeros = length - expectedZeros;

        final var gasRequirements = subject.transactionGasRequirements(Bytes.of(payload), false, null, null);

        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST
                        + HederaGasCalculatorImpl.TX_DATA_ZERO_COST * expectedZeros
                        + HederaGasCalculatorImpl.ISTANBUL_TX_DATA_NON_ZERO_COST * nonZeros,
                gasRequirements.intrinsicGas());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 16, 17})
    void payloadOfAllZerosCountsEveryByte(final int length) {
        final var payload = new byte[length]; // all zeros
        final var gasRequirements = subject.transactionGasRequirements(Bytes.of(payload), false, null, null);
        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST + HederaGasCalculatorImpl.TX_DATA_ZERO_COST * length,
                gasRequirements.intrinsicGas());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8, 9, 16, 17})
    void payloadWithNoZerosCountsNone(final int length) {
        final var payload = new byte[length];
        Arrays.fill(payload, (byte) 0x01); // exercises the SWAR borrow edge case (0x01 bytes)
        final var gasRequirements = subject.transactionGasRequirements(Bytes.of(payload), false, null, null);
        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST + HederaGasCalculatorImpl.ISTANBUL_TX_DATA_NON_ZERO_COST * length,
                gasRequirements.intrinsicGas());
    }

    @ParameterizedTest
    @CsvSource({
        // accessList
        "0,                     0",
        "1;0,                   0",
        "2;1,                   0",
        "2;1;0,                 0",
        "3;2;10;15;7;0;1,       0",
        // codeDelegations
        ",                      0",
        ",                      1",
        ",                      2",
        ",                      10",
        // accessList + codeDelegations
        "0,                     1",
        "1;0,                   2",
        "2;1;0,                 3",
    })
    void transactionGasRequirementsWithAccessListAndCode(
            final String keysCountString, final String codeDelegationsCountString) {
        // given
        final var keysCount = keysCountString == null
                ? List.<Integer>of()
                : Arrays.stream(keysCountString.split(";"))
                        .map(Integer::parseInt)
                        .toList();
        final var codeDelegationsCount = Integer.parseInt(codeDelegationsCountString);
        final var codeDelegations = TestTransactionUtils.generateAuthList(codeDelegationsCount);
        // when
        final var gasRequirements = subject.transactionGasRequirements(
                Bytes.EMPTY, false, TestTransactionUtils.generateAccessList(keysCount), codeDelegations);
        // then
        // intrinsicGas calculation with accessList from https://eips.ethereum.org/EIPS/eip-2930
        assertEquals(gasRequirements.intrinsicGas(), gasRequirements.minimumGasUsed());
        assertEquals(
                HederaGasCalculatorImpl.TX_BASE_COST
                        + 2_400L * keysCount.size()
                        + 1_900L * keysCount.stream().mapToInt(e -> e).sum()
                        + 25_000L * codeDelegationsCount,
                gasRequirements.intrinsicGas());
    }
}
