// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.stream.Stream;
import org.bouncycastle.crypto.digests.SHA1Digest;
import org.bouncycastle.crypto.prng.DigestRandomGenerator;
import org.bouncycastle.crypto.prng.RandomGenerator;
import org.bouncycastle.crypto.prng.VMPCRandomGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DetRandomTests {

    private static final long KNOWN_SEED = 0x4f4bd1fceebe47daL;

    private static final long[] KNOWN_SEQUENCE = {
        -2411724062467889592L, -6203376395929769067L, 2876443046104300518L, -2437510635463235683L,
        4097172332398540414L, 3469665326742451465L, 6125998513327554273L, 3441763992844700963L,
        4204465920141308254L, -911553069992821212L
    };

    private static final String MSG_VALUES_MUST_MATCH = "The original and generated values must match exactly";
    private static final String MSG_RANDOM_ARG_NOT_NULL = "The supplied random argument must not be null";
    private static final String MSG_DUP_RANDOM_ARG_NOT_NULL = "The supplied duplicateRandom argument must not be null";
    private static final String MSG_SEQUENCE_ARG_NOT_NULL = "The supplied desiredSequence argument must not be null";

    static Stream<Arguments> multipleRandomGeneratorSupplier() {
        return Stream.of(
                Arguments.of(
                        DigestRandomGenerator.class.getSimpleName(),
                        0L,
                        new DigestRandomGenerator(new SHA1Digest()),
                        new DigestRandomGenerator(new SHA1Digest())),
                Arguments.of(
                        DigestRandomGenerator.class.getSimpleName(),
                        480851022826L,
                        new DigestRandomGenerator(new SHA1Digest()),
                        new DigestRandomGenerator(new SHA1Digest())),
                Arguments.of(
                        VMPCRandomGenerator.class.getSimpleName(),
                        0L,
                        new VMPCRandomGenerator(),
                        new VMPCRandomGenerator()),
                Arguments.of(
                        VMPCRandomGenerator.class.getSimpleName(),
                        -605056342970L,
                        new VMPCRandomGenerator(),
                        new VMPCRandomGenerator()));
    }

    static Stream<Arguments> knownSequenceRandomGeneratorSupplier() {
        return Stream.of(Arguments.of(
                DigestRandomGenerator.class.getSimpleName(),
                KNOWN_SEED,
                new DigestRandomGenerator(new SHA1Digest()),
                KNOWN_SEQUENCE));
    }

    @ParameterizedTest(name = "[{index}] {0} (Seed: {1})")
    @MethodSource("multipleRandomGeneratorSupplier")
    @DisplayName("BouncyCastle PRNG Long Values - Verify Determinism")
    void verifyDeterministicLongValues(
            final String name, final long seed, final RandomGenerator random, final RandomGenerator duplicateRandom) {
        assertNotNull(random, MSG_RANDOM_ARG_NOT_NULL);
        assertNotNull(duplicateRandom, MSG_DUP_RANDOM_ARG_NOT_NULL);

        final long[] originalValues = new long[100];
        final long[] duplicateValues = new long[100];

        random.addSeedMaterial(seed);
        duplicateRandom.addSeedMaterial(seed);

        for (int i = 0; i < originalValues.length; i++) {
            final byte[] originalValue = new byte[Long.BYTES];
            final byte[] duplicateValue = new byte[Long.BYTES];
            random.nextBytes(originalValue);
            duplicateRandom.nextBytes(duplicateValue);
            originalValues[i] = bytesToLong(originalValue);
            duplicateValues[i] = bytesToLong(duplicateValue);
        }

        assertArrayEquals(originalValues, duplicateValues, MSG_VALUES_MUST_MATCH);
    }

    @ParameterizedTest(name = "[{index}] {0} (Seed: {1})")
    @MethodSource("multipleRandomGeneratorSupplier")
    @DisplayName("BouncyCastle PRNG Byte Values - Verify Determinism")
    void verifyDeterministicByteValues(
            final String name, final long seed, final RandomGenerator random, final RandomGenerator duplicateRandom) {
        assertNotNull(random, MSG_RANDOM_ARG_NOT_NULL);
        assertNotNull(duplicateRandom, MSG_DUP_RANDOM_ARG_NOT_NULL);

        final SecureRandom randomLengthCalculator = new SecureRandom();
        randomLengthCalculator.setSeed(Instant.now().toEpochMilli());

        final byte[][] originalValues = new byte[100][];
        final byte[][] duplicateValues = new byte[100][];

        random.addSeedMaterial(seed);
        duplicateRandom.addSeedMaterial(seed);

        for (int i = 0; i < originalValues.length; i++) {
            final int length = randomLengthCalculator.nextInt(512, 4096);
            final byte[] originalValue = new byte[length];
            final byte[] duplicateValue = new byte[length];
            random.nextBytes(originalValue);
            duplicateRandom.nextBytes(duplicateValue);
            originalValues[i] = originalValue;
            duplicateValues[i] = duplicateValue;
        }

        for (int i = 0; i < originalValues.length; i++) {
            assertArrayEquals(originalValues[i], duplicateValues[i], MSG_VALUES_MUST_MATCH);
        }
    }

    @ParameterizedTest(name = "[{index}] {0} (Seed: {1})")
    @MethodSource("knownSequenceRandomGeneratorSupplier")
    @DisplayName("BouncyCastle PRNG Long Values - Verify Known Sequence")
    void verifyKnownDeterministicSequence(
            final String name, final long seed, final RandomGenerator random, final long[] desiredSequence) {
        assertNotNull(random, MSG_RANDOM_ARG_NOT_NULL);
        assertNotNull(desiredSequence, MSG_SEQUENCE_ARG_NOT_NULL);

        random.addSeedMaterial(seed);

        final long[] newSequence = new long[desiredSequence.length];
        for (int i = 0; i < newSequence.length; i++) {
            final byte[] originalValue = new byte[Long.BYTES];
            final byte[] duplicateValue = new byte[Long.BYTES];
            random.nextBytes(originalValue);
            newSequence[i] = bytesToLong(originalValue);
        }

        assertArrayEquals(desiredSequence, newSequence, MSG_VALUES_MUST_MATCH);
    }

    private long bytesToLong(final byte[] bytes) {
        final ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return buffer.getLong();
    }
}
