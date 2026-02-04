// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.Random;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;
import org.hiero.base.crypto.SignatureType;

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
     * Get random signature bytes that is the same length as a RSA signature
     *
     * @return random signature bytes
     */
    @NonNull
    public Bytes nextSignatureBytes() {
        return randomBytes(SignatureType.RSA.signatureLength());
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
     * Generates random {@link Bytes} of the given size.
     *
     * @param size the number of random bytes to generate
     * @return random bytes
     */
    public Bytes randomBytes(final int size) {
        return Bytes.wrap(nextByteArray(size));
    }

    /**
     * Generates random {@link Bytes} with a length chosen uniformly at random between {@code originSize} (inclusive)
     * and {@code boundSize} (exclusive).
     *
     * @param originSize the minimum length (inclusive)
     * @param boundSize  the maximum length (exclusive)
     * @return random bytes of random length
     */
    public Bytes randomBytes(final int originSize, final int boundSize) {
        return Bytes.wrap(nextByteArray(this.nextInt(originSize, boundSize)));
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
     * Generates a random duration smaller than the supplied max duration
     *
     * @param maxDuration the upper bound, the returned duration will be smaller than this
     * @return a random duration
     * @throws IllegalArgumentException if maxDuration is negative or zero
     */
    @NonNull
    public Duration nextDuration(@NonNull final Duration maxDuration) {
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
        return Duration.ofNanos(this.nextPositiveLong(maxDuration.toNanos()));
    }

    /**
     * Generates a random duration between the supplied min and max durations
     *
     * @param minDuration the lower bound, the returned duration will be at least this long
     * @param maxDuration the upper bound, the returned duration will be smaller than this
     * @return a random duration
     * @throws IllegalArgumentException if minDuration is negative or zero,
     *                                  if maxDuration is negative or zero,
     *                                  or if minDuration is greater than maxDuration
     */
    @NonNull
    public Duration nextDuration(@NonNull final Duration minDuration, @NonNull final Duration maxDuration) {
        if (minDuration.isNegative() || minDuration.isZero()) {
            throw new IllegalArgumentException("minDuration must be positive");
        }
        if (maxDuration.isNegative() || maxDuration.isZero()) {
            throw new IllegalArgumentException("maxDuration must be positive");
        }
        if (minDuration.compareTo(maxDuration) > 0) {
            throw new IllegalArgumentException("minDuration must not be greater than maxDuration");
        }
        final long deltaNanos = maxDuration.toNanos() - minDuration.toNanos();
        return deltaNanos == 0L
                ? minDuration
                : Duration.ofNanos(minDuration.toNanos() + this.nextPositiveLong(deltaNanos + 1));
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

    @Override
    public String toString() {
        return "Randotron{" + seed + "L}";
    }
}
