// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class HintsFullGenerationThresholdNativeTest {
    @Test
    void nativeVerificationRequiresStrictMajorityOfTheFullGeneration() {
        final var library = new HintsLibraryImpl();
        final var initialCrs = library.newCrs((short) 4);
        final var entropy = new byte[32];
        entropy[0] = 1;
        final var crs = decodeCrsUpdate(initialCrs.length(), library.updateCrs(initialCrs, Bytes.wrap(entropy)))
                .crs();
        final var message = Bytes.wrap("block signed by a sparse hinTS scheme");
        final var privateKeys = new TreeMap<Integer, Bytes>();
        final var hints = new TreeMap<Integer, Bytes>();
        final var participantWeights = new TreeMap<Integer, Long>(Map.of(0, 10L, 1, 1L, 2, 7L));
        for (final var partyId : participantWeights.keySet()) {
            final var privateKey = library.newBlsPrivateKey();
            privateKeys.put(partyId, privateKey);
            hints.put(partyId, library.computeHints(crs, privateKey, partyId, 4));
        }
        final var keys = library.preprocess(crs, hints, participantWeights, 4);
        assertNotNull(keys);
        final var aggregationKey = Bytes.wrap(keys.aggregationKey());
        final var verificationKey = Bytes.wrap(keys.verificationKey());
        final var generation = new HintsGenerationWeights(
                1L,
                Bytes.wrap("generation-roster"),
                verificationKey,
                new TreeMap<>(Map.of(0L, 10L, 1L, 1L, 2L, 7L, 3L, 2L)),
                new TreeMap<>(Map.of(0L, 10L, 1L, 1L, 2L, 7L)),
                20L,
                18L);
        final var fraction = generation.verificationFraction(2);
        assertEquals(new HintsGenerationWeights.ThresholdFraction(5, 9), fraction);

        final var firstShare = library.signBls(message, privateKeys.get(0));
        final var weightTenSignature =
                library.aggregateSignatures(crs, aggregationKey, verificationKey, Map.of(0, firstShare));
        assertNotNull(weightTenSignature);
        // Ten exceeds half the encoded scheme weight, but is exactly half the full generation weight.
        assertTrue(library.verifyAggregate(weightTenSignature, message, verificationKey, 1, 2));
        assertFalse(library.verifyAggregate(
                weightTenSignature, message, verificationKey, fraction.numerator(), fraction.denominator()));

        final var secondShare = library.signBls(message, privateKeys.get(1));
        final var weightElevenSignature = library.aggregateSignatures(
                crs, aggregationKey, verificationKey, Map.of(0, firstShare, 1, secondShare));
        assertNotNull(weightElevenSignature);
        assertTrue(library.verifyAggregate(
                weightElevenSignature, message, verificationKey, fraction.numerator(), fraction.denominator()));
    }
}
