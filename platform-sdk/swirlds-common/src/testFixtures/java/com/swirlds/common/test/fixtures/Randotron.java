// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.crypto.SignatureType;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.Random;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.crypto.Hash;

/**
 * An extension of the Random class that provides additional utility methods for generating random data.
 */
public final class Randotron extends Random {
    private final long seed;

    /**
     * Create a new instance of Randotron
     *
     * @return a new instance of Randotron
     */
    public static Randotron create() {
        final long seed = new Random().nextLong();
        System.out.println("Random seed: " + seed + "L");
        return new Randotron(seed);
    }

    /**
     * Create a new instance of Randotron with the given seed
     *
     * @param seed the seed to use
     * @return a new instance of Randotron
     */
    public static Randotron create(final long seed) {
        return new Randotron(seed);
    }

    /**
     * The ONLY permitted constructor.
     * <p>
     * Do NOT implement an unseeded constructor.
     *
     * @param seed the random seed
     */
    private Randotron(final long seed) {
        super(seed);
        this.seed = seed;
    }

    /**
     * Get a copy of this Randotron with the same starting seed. The copy will have the same state as this Randotron at
     * the moment it was first created (not the current state!).
     *
     * @return a copy of this Randotron
     */
    public Randotron copyAndReset() {
        return new Randotron(seed);
    }

    /**
     * Generates a random string of the given length.
     *
     * @param length the length of the string to generate
     * @return a random string
     */
    @NonNull
    public String nextString(final int length) {
        final int LEFT_LIMIT = 48; // numeral '0'
        final int RIGHT_LIMIT = 122; // letter 'z'

        return this.ints(LEFT_LIMIT, RIGHT_LIMIT + 1)
                .filter(i -> (i <= 57 || i >= 65) && (i <= 90 || i >= 97))
                .limit(length)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    /**
     * Generates a random IP address
     *
     * @return a random IP address
     */
    @NonNull
    public String nextIp() {
        return this.nextInt(256) + "." + this.nextInt(256) + "." + this.nextInt(256) + "." + this.nextInt(256);
    }

    /**
     * Generates a random positive long that is smaller than the supplied value
     *
     * @param maxValue the upper bound, the returned value will be smaller than this
     * @return the random long
     */
    public long nextPositiveLong(final long maxValue) {
        return this.longs(1, 1, maxValue).findFirst().orElseThrow();
    }

    /**
     * Generates a random positive long
     *
     * @return the random long
     */
    public long nextPositiveLong() {
        return nextPositiveLong(Long.MAX_VALUE);
    }

    /**
     * Generates a random positive int that is smaller than the supplied value
     *
     * @param maxValue the upper bound, the returned value will be smaller than this
     * @return the random int
     */
    public int nextPositiveInt(final int maxValue) {
        return this.ints(1, 1, maxValue).findFirst().orElseThrow();
    }

    /**
     * Generates a random positive int
     *
     * @return the random int
     */
    public int nextPositiveInt() {
        return nextPositiveInt(Integer.MAX_VALUE);
    }

    /**
     * Generates a random hash
     *
     * @return a random hash
     */
    @NonNull
    public Hash nextHash() {
        return new Hash(nextByteArray(DigestType.SHA_384.digestLength()), DigestType.SHA_384);
    }

    /**
     * Generates Bytes with random data that is the same length as a SHA-384 hash
     *
     * @return random Bytes
     */
    @NonNull
    public Bytes nextHashBytes() {
        return Bytes.wrap(nextByteArray(DigestType.SHA_384.digestLength()));
    }

    /**
     * Get a random signature (doesn't actually sign anything, just random bytes)
     *
     * @return a random signature
     */
    @NonNull
    public Signature nextSignature() {
        return new Signature(SignatureType.RSA, nextByteArray(SignatureType.RSA.signatureLength()));
    }

    /**
     * Get random signature bytes that is the same length as a RSA signature
     *
     * @return random signature bytes
     */
    @NonNull
    public Bytes nextSignatureBytes() {
        return Bytes.wrap(nextByteArray(SignatureType.RSA.signatureLength()));
    }

    /**
     * Generates a random byte array
     *
     * @param size the size of the byte array
     * @return a random byte array
     */
    @NonNull
    public byte[] nextByteArray(final int size) {
        final byte[] bytes = new byte[size];
        this.nextBytes(bytes);
        return bytes;
    }

    /**
     * Generates a random instant
     *
     * @return a random instant
     */
    @NonNull
    public Instant nextInstant() {
        return Instant.ofEpochMilli(nextPositiveLong(2000000000000L));
    }

    /**
     * Generates a random boolean with a given probability of being true
     *
     * @param trueProbability the probability of the boolean being true
     * @return a random boolean
     */
    public boolean nextBoolean(final double trueProbability) {
        if (trueProbability < 0 || trueProbability > 1) {
            throw new IllegalArgumentException("Probability must be between 0 and 1");
        }

        return this.nextDouble() < trueProbability;
    }

    /**
     * Get the seed used to create this Randotron instance
     *
     * @return the seed
     */
    public long getSeed() {
        return seed;
    }
}
