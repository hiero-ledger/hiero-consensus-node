// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static java.util.stream.Collectors.toMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private final HintsLibraryImpl subject = new HintsLibraryImpl();

    @Test
    void generatesNewCrs() {
        assertNotNull(subject.newCrs((short) 10));
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
    void generatesNewBlsPrivateKey() {
        assertNotNull(subject.newBlsPrivateKey());
    }

    @Test
    void computesAndValidateHints() {
        final var crs = subject.newCrs((short) 256);
        final var blsPrivateKey = subject.newBlsPrivateKey();
        final var hints = subject.computeHints(crs, blsPrivateKey, 1, 16);
        assertNotNull(hints);
        assertNotEquals(hints, Bytes.EMPTY);

        final var isValid = subject.validateHintsKey(crs, hints, 1, 16);
        assertTrue(isValid);
    }

    @Test
    void preprocessesHintsIntoUsableKeys() {
        final var initialCrs = subject.newCrs((short) 64);
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
        assertEquals(1288L, vk.length());

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
        final var message = "Hello World".getBytes();
        final var blsPrivateKey = subject.newBlsPrivateKey();
        final var crs = subject.newCrs((short) 8);
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
    void aggregatesAndVerifiesSignatures() {
        // When CRS is for n, then signers should be  n - 1
        final var crs = subject.newCrs((short) 4);

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
