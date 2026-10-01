// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DigestTypeTest {

    /**
     * The expected characteristics of each digest type. The ids are written to streams, so they must never change.
     */
    static Stream<Arguments> digestTypeCharacteristics() {
        return Stream.of(
                Arguments.of(DigestType.SHA_256, 0x1c15d3fb, "SHA-256", "SUN", 32),
                Arguments.of(DigestType.SHA_384, 0x58ff811b, "SHA-384", "SUN", 48),
                Arguments.of(DigestType.SHA_512, 0x8fc9497e, "SHA-512", "SUN", 64));
    }

    @ParameterizedTest
    @MethodSource("digestTypeCharacteristics")
    void characteristics(
            final DigestType digestType,
            final int id,
            final String algorithmName,
            final String provider,
            final int digestLength) {
        assertEquals(id, digestType.id(), "id must not change, it is used for serialization");
        assertEquals(algorithmName, digestType.algorithmName());
        assertEquals(provider, digestType.provider());
        assertEquals(digestLength, digestType.digestLength());
    }

    @Test
    void allDigestTypesHaveExpectedCharacteristics() {
        // fails if a digest type is added without updating this test
        assertEquals(digestTypeCharacteristics().count(), DigestType.values().length);
    }

    @Test
    void idsAreUnique() {
        assertEquals(
                DigestType.values().length,
                Arrays.stream(DigestType.values())
                        .mapToInt(DigestType::id)
                        .distinct()
                        .count());
    }

    @Test
    void digestLengthsAreUnique() {
        // digestLengthToDigestType() relies on each length mapping to at most one digest type
        assertEquals(
                DigestType.values().length,
                Arrays.stream(DigestType.values())
                        .mapToInt(DigestType::digestLength)
                        .distinct()
                        .count());
    }

    @Test
    void maxLengthIsLargestDigestLength() {
        final int largest = Arrays.stream(DigestType.values())
                .mapToInt(DigestType::digestLength)
                .max()
                .orElseThrow();
        assertEquals(largest, DigestType.getMaxLength());
    }

    @ParameterizedTest
    @EnumSource(DigestType.class)
    void valueOfIdRoundTrips(final DigestType digestType) {
        assertEquals(digestType, DigestType.valueOf(digestType.id()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void valueOfUnknownIdReturnsNull(final int id) {
        assertNull(DigestType.valueOf(id));
    }

    @ParameterizedTest
    @EnumSource(DigestType.class)
    void algorithmNameRoundTrips(final DigestType digestType) {
        assertEquals(digestType, DigestType.algorithmNameToDigestType(digestType.algorithmName()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "SHA-1", "MD5", "sha-256", "SHA256", "SHA3-256"})
    void unknownAlgorithmNameReturnsNull(final String algorithmName) {
        assertNull(DigestType.algorithmNameToDigestType(algorithmName));
    }

    @ParameterizedTest
    @EnumSource(DigestType.class)
    void digestLengthRoundTrips(final DigestType digestType) {
        assertEquals(digestType, DigestType.digestLengthToDigestType(digestType.digestLength()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 1, 20, 31, 33, 47, 49, 63, 65, 128})
    void unknownDigestLengthReturnsNull(final int digestLength) {
        assertNull(DigestType.digestLengthToDigestType(digestLength));
    }

    @ParameterizedTest
    @EnumSource(DigestType.class)
    void buildDigestMatchesDigestType(final DigestType digestType) {
        final MessageDigest digest = digestType.buildDigest();

        assertEquals(digestType.algorithmName(), digest.getAlgorithm());
        assertEquals(digestType.digestLength(), digest.getDigestLength());
        assertEquals(digestType.digestLength(), digest.digest("data".getBytes(StandardCharsets.UTF_8)).length);
    }

    @ParameterizedTest
    @EnumSource(DigestType.class)
    void buildDigestReturnsNewInstance(final DigestType digestType) {
        assertNotSame(digestType.buildDigest(), digestType.buildDigest());
    }

    /**
     * Known answers for the input "abc" from FIPS 180-2.
     */
    static Stream<Arguments> knownAnswers() {
        return Stream.of(
                Arguments.of(DigestType.SHA_256, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
                Arguments.of(
                        DigestType.SHA_384,
                        "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed"
                                + "8086072ba1e7cc2358baeca134c825a7"),
                Arguments.of(
                        DigestType.SHA_512,
                        "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                                + "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"));
    }

    @ParameterizedTest
    @MethodSource("knownAnswers")
    void buildDigestProducesKnownAnswer(final DigestType digestType, final String expectedHex) {
        final byte[] actual = digestType.buildDigest().digest("abc".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals(HexFormat.of().parseHex(expectedHex), actual);
    }
}
