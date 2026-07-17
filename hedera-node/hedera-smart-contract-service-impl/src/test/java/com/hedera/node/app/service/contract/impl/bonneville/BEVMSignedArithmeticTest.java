// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.bonneville;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Known-answer tests for signed EVM arithmetic (SDIV/SMOD) in {@link BEVM}, checked against the EVM
 * specification (evm.codes) with stack words treated as 256-bit two's-complement.
 */
class BEVMSignedArithmeticTest {

    // -2^255: the most-negative 256-bit word.
    private static final BigInteger MIN = BigInteger.TWO.pow(255).negate();

    @ParameterizedTest(name = "{0} SDIV {1} = {2}")
    @MethodSource("sdivCases")
    void sdivMatchesEvmSpec(BigInteger a, BigInteger b, BigInteger expected) {
        assertEquals(expected, sdiv(a, b));
    }

    static Stream<Arguments> sdivCases() {
        return Stream.of(
                Arguments.of(bi(7), bi(3), bi(2)),
                Arguments.of(bi(-3), bi(2), bi(-1)), // truncates toward zero
                Arguments.of(bi(-9), bi(2), bi(-4)),
                Arguments.of(bi(-6), bi(2), bi(-3)),
                Arguments.of(bi(9), bi(2), bi(4)),
                Arguments.of(bi(-7), bi(3), bi(-2)),
                Arguments.of(bi(-7), bi(-3), bi(2)),
                Arguments.of(bi(7), bi(-3), bi(-2)),
                Arguments.of(MIN, bi(-1), MIN), // -2^255 / -1 overflows back to -2^255
                Arguments.of(bi(5), bi(0), bi(0)), // divide by zero -> 0
                Arguments.of(bi(0), bi(5), bi(0)));
    }

    @ParameterizedTest(name = "{0} SMOD {1} = {2}")
    @MethodSource("smodCases")
    void smodMatchesEvmSpec(BigInteger a, BigInteger b, BigInteger expected) {
        assertEquals(expected, smod(a, b));
    }

    static Stream<Arguments> smodCases() {
        return Stream.of(
                Arguments.of(bi(-7), bi(3), bi(-1)), // sign follows the dividend
                Arguments.of(bi(7), bi(-3), bi(1)),
                Arguments.of(bi(-7), bi(-3), bi(-1)),
                Arguments.of(bi(7), bi(3), bi(1)),
                Arguments.of(bi(5), bi(5), bi(0)), // a % a
                Arguments.of(bi(5), bi(0), bi(0)), // mod by zero -> 0
                Arguments.of(bi(0), bi(5), bi(0)));
    }

    // push/pop must preserve the full 256-bit signed word.
    @ParameterizedTest(name = "push/pop {0}")
    @MethodSource("roundTripValues")
    void pushRoundTripsSignedWord(BigInteger v) {
        final var evm = new BEVM();
        evm.push(v);
        assertEquals(v, signed(evm.popBytes().toArrayUnsafe()));
    }

    static Stream<Arguments> roundTripValues() {
        return Stream.of(
                Arguments.of(bi(-1)),
                Arguments.of(bi(-3)),
                Arguments.of(bi(-255)),
                Arguments.of(MIN),
                Arguments.of(bi(0)),
                Arguments.of(bi(42)),
                Arguments.of(BigInteger.TWO.pow(255).subtract(BigInteger.ONE))); // max positive word
    }

    // Drive "a SDIV b" through the interpreter stack (dividend on top) and read back the signed result.
    private static BigInteger sdiv(BigInteger a, BigInteger b) {
        final var evm = new BEVM();
        evm.push(b);
        evm.push(a);
        evm.sdiv();
        return signed(evm.popBytes().toArrayUnsafe());
    }

    private static BigInteger smod(BigInteger a, BigInteger b) {
        final var evm = new BEVM();
        evm.push(b);
        evm.push(a);
        evm.smod();
        return signed(evm.popBytes().toArrayUnsafe());
    }

    private static BigInteger signed(byte[] word32) {
        return new BigInteger(word32); // 32-byte two's-complement -> signed value
    }

    private static BigInteger bi(long v) {
        return BigInteger.valueOf(v);
    }
}
