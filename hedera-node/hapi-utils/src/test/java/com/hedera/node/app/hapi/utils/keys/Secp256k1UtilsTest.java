// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.keys;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.cryptography.libsecp256k1.Secp256k1ECPrivateKey;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class Secp256k1UtilsTest {

    static Stream<? extends Arguments> bigIntegerArguments() {
        return Stream.of(
                Arguments.of(BigInteger.valueOf(-10000), false),
                Arguments.of(BigInteger.valueOf(-1), false),
                Arguments.of(BigInteger.valueOf(0), false),
                Arguments.of(BigInteger.valueOf(1), true),
                Arguments.of(BigInteger.valueOf(2), true),
                Arguments.of(BigInteger.valueOf(1000000), true),
                Arguments.of(Secp256k1ECPrivateKey.SECP256K1_CURVE_N.subtract(BigInteger.TEN), true),
                Arguments.of(Secp256k1ECPrivateKey.SECP256K1_CURVE_N.subtract(BigInteger.ONE), true),
                Arguments.of(Secp256k1ECPrivateKey.SECP256K1_CURVE_N, false),
                Arguments.of(Secp256k1ECPrivateKey.SECP256K1_CURVE_N.add(BigInteger.ONE), false),
                Arguments.of(Secp256k1ECPrivateKey.SECP256K1_CURVE_N.add(BigInteger.TEN), false));
    }

    static Stream<? extends Arguments> arrayArguments() {
        return Stream.of(
                Arguments.of(
                        HexFormat.of().parseHex("0000000000000000000000000000000000000000000000000000000000000000"),
                        false),
                Arguments.of(
                        HexFormat.of().parseHex("0000000000000000000000000000000000000000000000000000000000000001"),
                        true),
                Arguments.of(
                        HexFormat.of().parseHex("0000000000000000000000000000000000000000000000000000000000000011"),
                        true),
                Arguments.of(
                        HexFormat.of().parseHex("0000000000000000000000000000000000000000000000000000000000000011"),
                        true),
                Arguments.of(
                        HexFormat.of().parseHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364140"),
                        true),
                Arguments.of(
                        HexFormat.of().parseHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141"),
                        false),
                Arguments.of(
                        HexFormat.of().parseHex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364541"),
                        false),
                Arguments.of(
                        HexFormat.of().parseHex("fffffffffffffffffffffffffffffffffffffffffffffffffffffffffff5ffff"),
                        false),
                Arguments.of(
                        HexFormat.of().parseHex("fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffe"),
                        false),
                Arguments.of(
                        HexFormat.of().parseHex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
                        false));
    }

    @ParameterizedTest
    @MethodSource("bigIntegerArguments")
    void testBigIntegers(BigInteger value, boolean isGood) {
        testValue(value, isGood, Secp256k1Utils::asPrivateKeyByteArray32);
        testValue(value, isGood, Secp256k1Utils::readECKeyFrom);
    }

    @ParameterizedTest
    @MethodSource("arrayArguments")
    void testArrays(byte[] value, boolean isGood) {
        testValue(value, isGood, Secp256k1Utils::readECKeyFrom);
    }

    <T> void testValue(T value, boolean isGood, Consumer<T> caller) {
        if (isGood) {
            caller.accept(value);
        } else {
            assertThrows(IllegalArgumentException.class, () -> caller.accept(value));
        }
    }
}
