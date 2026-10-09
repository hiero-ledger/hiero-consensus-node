// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HintsLibraryImplTest {
    private static final byte[] CRS_CONTRIBUTION = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
        30, 31
    };
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private final HintsLibraryImpl subject = new HintsLibraryImpl();

    @Test
    void decodesExactCapacityAndRejectsInvalidSizes() {
        assertEquals(8, subject.crsPartySize(subject.newCrs((short) 8)));
        assertEquals(512, subject.crsPartySize(Bytes.wrap(new byte[304 + 512 * 288])));
        for (final int size : new int[] {0, 304, 304 + 8 * 288 + 1, 304 + 3 * 288, 304 + 1024 * 288}) {
            assertThrows(IllegalArgumentException.class, () -> subject.crsPartySize(Bytes.wrap(new byte[size])));
        }
    }

    @Test
    void rejectsImplicitlyResizingCrs() {
        final var crs = subject.newCrs((short) 8);
        final var key = subject.newBlsPrivateKey();
        assertThrows(IllegalArgumentException.class, () -> subject.computeHints(crs, key, 0, 4));
        assertThrows(IllegalArgumentException.class, () -> subject.validateHintsKey(crs, Bytes.EMPTY, 0, 4));
        assertThrows(
                IllegalArgumentException.class, () -> subject.preprocess(crs, new TreeMap<>(), new TreeMap<>(), 4));
    }

    @Test
    void switchesNativeCachesAcrossCrsAndAggregationKeys() {
        final var message = Bytes.wrap("interleaved native signing");
        final var first = schemeFor(subject.newCrs((short) 8), 8, message);
        final var second = schemeFor(subject.newCrs((short) 16), 16, message);
        assertSigns(first, message);
        assertSigns(second, message);
        final var third = schemeFor(first.crs(), 8, message);
        assertSigns(third, message);
        assertSigns(first, message);
        assertSigns(second, message);
        assertSigns(first, message);
    }

    private record Scheme(Bytes crs, Bytes ak, Bytes vk, Bytes signature) {}

    private Scheme schemeFor(final Bytes crs, final int n, final Bytes message) {
        final var privateKey = subject.newBlsPrivateKey();
        final var hint = subject.computeHints(crs, privateKey, 0, n);
        final var keys = subject.preprocess(crs, new TreeMap<>(Map.of(0, hint)), new TreeMap<>(Map.of(0, 1L)), n);
        return new Scheme(
                crs,
                Bytes.wrap(keys.aggregationKey()),
                Bytes.wrap(keys.verificationKey()),
                subject.signBls(message, privateKey));
    }

    private void assertSigns(final Scheme scheme, final Bytes message) {
        assertTrue(subject.verifyBls(scheme.crs(), scheme.signature(), message, scheme.ak(), 0));
        final var aggregate =
                subject.aggregateSignatures(scheme.crs(), scheme.ak(), scheme.vk(), Map.of(0, scheme.signature()));
        assertTrue(subject.verifyAggregate(aggregate, message, scheme.vk(), 1L, 3L));
    }

    @Test
    void generatesNewCrs() {
        assertNotNull(subject.newCrs((short) 16));
    }

    @Test
    void updatesCrs() {
        final var oldCrs = subject.newCrs((short) 2);
        byte[] entropyBytes = new byte[32];
        RANDOM.nextBytes(entropyBytes);
        final var newCrs = subject.updateCrs(oldCrs, Bytes.wrap(entropyBytes));
        assertNotNull(newCrs);
        assertNotEquals(oldCrs, newCrs);
    }

    @Test
    void verifiesCrs() {
        final var oldCrs = subject.newCrs((short) 4);
        byte[] entropyBytes = new byte[32];
        RANDOM.nextBytes(entropyBytes);
        final var newCrs = subject.updateCrs(oldCrs, Bytes.wrap(entropyBytes));
        final var decodedCrsUpdate = decodeCrsUpdate(oldCrs.length(), newCrs);
        final var isValid = subject.verifyCrsUpdate(oldCrs, newCrs, decodedCrsUpdate.proof());
        assertTrue(isValid);
    }

    @Test
    void malformedCrsInputsReturnNullOrFalse() {
        final var oldCrs = subject.newCrs((short) 4);

        assertNull(subject.updateCrs(oldCrs, Bytes.wrap(new byte[31])));
        assertFalse(subject.verifyCrsUpdate(oldCrs, Bytes.wrap("malformed"), Bytes.wrap("proof")));
    }

    @Test
    void generatesNewBlsPrivateKey() {
        assertNotNull(subject.newBlsPrivateKey());
    }

    @Test
    void computesAndValidateHints() {
        subject.resetCache();

        var crs = subject.newCrs((short) 16);
        crs = decodeCrsUpdate(crs.length(), subject.updateCrs(crs, Bytes.wrap(CRS_CONTRIBUTION)))
                .crs();
        final var blsPrivateKey = subject.newBlsPrivateKey();
        final var hints = subject.computeHints(crs, blsPrivateKey, 1, 16);
        assertNotNull(hints);
        assertNotEquals(hints, Bytes.EMPTY);

        final var isValid = subject.validateHintsKey(crs, hints, 1, 16);
        assertTrue(isValid);
    }

    @Test
    void preprocessesHintsIntoUsableKeys() {
        subject.resetCache();

        final var initialCrs = subject.newCrs((short) 4);
        byte[] entropyBytes = new byte[32];
        RANDOM.nextBytes(entropyBytes);
        final var newCrs = subject.updateCrs(initialCrs, Bytes.wrap(entropyBytes));
        final var decodedCrsUpdate = decodeCrsUpdate(initialCrs.length(), newCrs);
        final var crs = decodedCrsUpdate.crs();

        final int numParties = 4;
        // (FUTURE) Understand why this test doesn't pass with List.of(1, 2, 3)
        final List<Integer> ids = List.of(0, 1, 2);
        final Map<Integer, Bytes> privateKeys = IntStream.range(0, numParties)
                .boxed()
                .collect(toMap(Function.identity(), i -> subject.newBlsPrivateKey()));

        final SortedMap<Integer, Bytes> hintsKeys = new TreeMap<>();
        final List<Integer> knownParties = ids;
        for (final int partyId : knownParties) {
            hintsKeys.put(partyId, subject.computeHints(crs, privateKeys.get(partyId), partyId, numParties));
        }

        final SortedMap<Integer, Long> weights = new TreeMap<>();
        for (final int partyId : knownParties) {
            weights.put(partyId, 1L);
        }
        final var keys = subject.preprocess(crs, hintsKeys, weights, numParties);
        final var ak = Bytes.wrap(keys.aggregationKey());
        final var vk = Bytes.wrap(keys.verificationKey());
        assertEquals(1712L, ak.length());
        assertEquals(HintsLibraryImpl.VK_LENGTH, vk.length());

        final var message = Bytes.wrap("Hello World");
        final List<Integer> signingParties = ids;
        final var signatures = signingParties.stream()
                .collect(toMap(Function.identity(), partyId -> subject.signBls(message, privateKeys.get(partyId))));
        signatures.forEach((partyId, s) -> assertTrue(subject.verifyBls(crs, s, message, ak, partyId)));
        final var sig = subject.aggregateSignatures(crs, ak, vk, signatures);
        assertTrue(subject.verifyAggregate(sig, message, vk, 1, 3));
    }

    @Test
    void signsAndVerifiesBlsSignature() {
        subject.resetCache();

        final var message = "Hello World".getBytes();
        final var blsPrivateKey = subject.newBlsPrivateKey();
        final var crs = subject.newCrs((short) 4);
        final int partyId = 0;
        final var extendedPublicKey = subject.computeHints(crs, blsPrivateKey, partyId, 4);
        final var signature = subject.signBls(Bytes.wrap(message), blsPrivateKey);
        assertNotNull(signature);

        final SortedMap<Integer, Bytes> hintsForAllParties = new TreeMap<>();
        hintsForAllParties.put(partyId, extendedPublicKey);

        final SortedMap<Integer, Long> weights = new TreeMap<>();
        weights.put(partyId, 1L);

        final var keys = subject.preprocess(crs, hintsForAllParties, weights, 4);

        final var isValid =
                subject.verifyBls(crs, signature, Bytes.wrap(message), Bytes.wrap(keys.aggregationKey()), partyId);
        assertTrue(isValid);
    }

    @Test
    void rejectsTooShortAggregationKeyWithoutCrashing() {
        final var message = Bytes.wrap("Hello World");
        final var signature = Bytes.wrap("signature");
        final var crs = subject.newCrs((short) 8);
        final var tinyAggregationKey = Bytes.wrap(new byte[48]);
        final var verificationKey = Bytes.wrap("verificationKey");

        assertFalse(subject.verifyBls(crs, signature, message, tinyAggregationKey, 0));
        assertNull(subject.aggregateSignatures(crs, tinyAggregationKey, verificationKey, Map.of(0, signature)));
    }

    @Test
    void aggregatesAndVerifiesSignatures() {
        subject.resetCache();

        // When CRS is for n, then signers should be  n - 1
        var crs = subject.newCrs((short) 4);
        crs = decodeCrsUpdate(crs.length(), subject.updateCrs(crs, Bytes.wrap(CRS_CONTRIBUTION)))
                .crs();

        final var secretKey1 = subject.newBlsPrivateKey();
        final var hints1 = subject.computeHints(crs, secretKey1, 0, 4);

        final var secretKey2 = subject.newBlsPrivateKey();
        final var hints2 = subject.computeHints(crs, secretKey2, 1, 4);

        final var secretKey3 = subject.newBlsPrivateKey();
        final var hints3 = subject.computeHints(crs, secretKey3, 2, 4);

        final SortedMap<Integer, Bytes> hintsForAllParties = new TreeMap<>();
        hintsForAllParties.put(0, hints1);
        hintsForAllParties.put(1, hints2);
        hintsForAllParties.put(2, hints3);

        final SortedMap<Integer, Long> weights = new TreeMap<>();
        weights.put(0, 200L);
        weights.put(1, 300L);
        weights.put(2, 400L);

        final var keys = subject.preprocess(crs, hintsForAllParties, weights, 4);

        final var message = Bytes.wrap("Hello World".getBytes());
        final var signature1 = subject.signBls(message, secretKey1);
        final var signature2 = subject.signBls(message, secretKey2);
        final var signature3 = subject.signBls(message, secretKey3);

        final var aggregatedSignature = subject.aggregateSignatures(
                crs,
                Bytes.wrap(keys.aggregationKey()),
                Bytes.wrap(keys.verificationKey()),
                Map.of(0, signature1, 1, signature2, 2, signature3));

        final var isValid =
                subject.verifyAggregate(aggregatedSignature, message, Bytes.wrap(keys.verificationKey()), 1, 4);
        assertTrue(isValid);
    }
}
