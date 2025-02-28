// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class HintsLibraryImplTest {
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private final HintsLibraryImpl subject = new HintsLibraryImpl();
    private final HintsLibraryCodec codec = new HintsLibraryCodec();

    @Test
    void generatesNewCrs() {
        assertNotNull(subject.newCrs(10));
    }

    @Test
    void updatesCrs() {
        final var oldCrs = subject.newCrs(2);
        byte[] entropyBytes = new byte[32];
        RANDOM.nextBytes(entropyBytes);
        final var newCrs = subject.updateCrs(oldCrs, Bytes.wrap(entropyBytes));
        assertNotNull(newCrs);
        assertNotEquals(oldCrs, newCrs);
    }

    @Test
    void verifiesCrs() {
        final var oldCrs = subject.newCrs(4);
        byte[] entropyBytes = new byte[32];
        RANDOM.nextBytes(entropyBytes);
        final var newCrs = subject.updateCrs(oldCrs, Bytes.wrap(entropyBytes));
        final var decodedCrsUpdate = codec.decodeCrsUpdate(newCrs);
        final var isValid = subject.verifyCrsUpdate(oldCrs, newCrs, decodedCrsUpdate.proof());
        assertTrue(isValid);
    }

    @Test
    void generatesNewBlsKeyPair() {
        assertNotNull(subject.newBlsKeyPair());
    }

    @Test
    void computesAndValidateHints() {
        final var crs = subject.newCrs(4);
        final var blsPrivateKey = subject.newBlsKeyPair();
        final var hints = subject.computeHints(crs, blsPrivateKey, 1, 4);
        assertNotNull(hints);
        assertNotEquals(hints, Bytes.EMPTY);

        final var isValid = subject.validateHintsKey(crs, hints, 1, 4);
        assertTrue(isValid);
    }

    @Test
    void preprocessesHints() {
        final var crs = subject.newCrs(4);
        byte[] entropyBytes = new byte[32];
        RANDOM.nextBytes(entropyBytes);
        final var newCrs = subject.updateCrs(crs, Bytes.wrap(entropyBytes));
        final var decodedCrsUpdate = codec.decodeCrsUpdate(newCrs);

        final var newCrsBytes = decodedCrsUpdate.crs();
        final var blsPrivateKey = subject.newBlsKeyPair();

        final var hintsForAllParties = Map.of(
                1, subject.computeHints(newCrsBytes, blsPrivateKey, 1, 4),
                2, subject.computeHints(newCrsBytes, blsPrivateKey, 2, 4),
                3, subject.computeHints(newCrsBytes, blsPrivateKey, 3, 4));

        final var aggregationAndVerificationKeys =
                subject.preprocess(newCrsBytes, hintsForAllParties, Map.of(1, 200L, 2, 300L, 3, 400L), 4);
        assertNotNull(aggregationAndVerificationKeys);
        assertNotEquals(aggregationAndVerificationKeys.aggregationKey(), new byte[0]);
        assertNotEquals(aggregationAndVerificationKeys.verificationKey(), new byte[0]);

        assertEquals(1712, aggregationAndVerificationKeys.aggregationKey().length);
        assertEquals(1288, aggregationAndVerificationKeys.verificationKey().length);
    }

    @Test
    void signsAndVerifiesBlsSignature() {
        final var message = "Hello World".getBytes();
        final var blsPrivateKey = subject.newBlsKeyPair();
        final var crs = subject.newCrs(4);
        final var extendedPublicKey = subject.computeHints(crs, blsPrivateKey, 1, 4);
        final var signature = subject.signBls(Bytes.wrap(message), blsPrivateKey);
        assertNotNull(signature);

        final var isValid = subject.verifyBls(crs, signature, Bytes.wrap(message), extendedPublicKey);
        assertTrue(isValid);
    }

    @Test
    void aggregatesAndVerifiesSignatures() {
        // When CRS is for n, then signers should be  n - 1
        final var crs = subject.newCrs(4);

        final var secretKey1 = subject.newBlsKeyPair();
        final var hints1 = subject.computeHints(crs, secretKey1, 0, 4);

        final var secretKey2 = subject.newBlsKeyPair();
        final var hints2 = subject.computeHints(crs, secretKey2, 1, 4);

        final var secretKey3 = subject.newBlsKeyPair();
        final var hints3 = subject.computeHints(crs, secretKey3, 2, 4);

        final var hintsForAllParties = Map.of(
                0, hints1,
                1, hints2,
                2, hints3);

        final var keys = subject.preprocess(crs, hintsForAllParties, Map.of(0, 200L, 1, 300L, 2, 400L), 4);

        final var message = Bytes.wrap("Hello World".getBytes());
        final var signature1 = subject.signBls(message, secretKey1);
        final var signature2 = subject.signBls(message, secretKey2);
        final var signature3 = subject.signBls(message, secretKey3);

        final var aggregatedSignature = subject.aggregateSignatures(
                crs,
                Bytes.wrap(keys.aggregationKey()),
                Bytes.wrap(keys.verificationKey()),
                Map.of(0, signature1, 1, signature2, 2, signature3));

        assertNotNull(aggregatedSignature);
        assertNotEquals(aggregatedSignature, Bytes.EMPTY);

        final var isValid =
                subject.verifyAggregate(crs, aggregatedSignature, message, Bytes.wrap(keys.verificationKey()), 1, 4);
        assertTrue(isValid);
    }
}
