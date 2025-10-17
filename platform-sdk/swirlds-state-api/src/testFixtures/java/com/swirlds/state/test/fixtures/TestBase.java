// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A convenient base class from which other tests can extend to get access to common APIs and
 * utilities.
 */
// Suppress the warning that we shouldn't throw generic exceptions(RuntimeException in static())
@SuppressWarnings("java:S112")
public class TestBase {

    /** All the ASCII capital letters */
    public static final String CAPITALS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    /** A set of words. Used to generate random word lists */
    private static final Set<String> WORDS;

    static {
        final var words = new HashSet<String>();
        try (final var input = TestBase.class.getResourceAsStream("wordlist.txt")) {
            assert input != null : "Missing wordlist.txt";
            final var reader = new BufferedReader(new InputStreamReader(input));
            String word;
            while ((word = reader.readLine()) != null) {
                words.add(word);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load word list!", e);
        }

        WORDS = Collections.unmodifiableSet(words);
    }

    /**
     * A pseudorandom number generator used for "random" values. We use a pseudorandom generator so
     * that tests are random, but repeatable. There is one of these PER TEST CLASS, so that test
     * classes can run in parallel while keeping the order of generated random numbers deterministic
     * between runs. If a single test class executes its methods in parallel, then beware!
     */
    protected final Random rand = new Random(9239992);

    /**
     * Gets the random number generator used by this instance
     *
     * @return the random number generator
     */
    @NonNull
    public final Random random() {
        return rand;
    }

    /**
     * Generates some random bytes
     *
     * @param length The number of bytes to generate.
     * @return Some random bytes.
     */
    @NonNull
    public final Bytes randomBytes(int length) {
        return Bytes.wrap(randomBytes(rand, length));
    }

    /**
     * Generates some random bytes
     *
     * @param length The number of bytes to generate.
     * @return Some random bytes.
     */
    @NonNull
    public final byte[] randomByteArray(int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) rand.nextInt();
        }
        return data;
    }

    /**
     * Gets the word list.
     *
     * @return An unmodifiable set of words
     */
    public Set<String> words() {
        return WORDS;
    }

    /**
     * A {@link List} of random words from the set of {@link #words()}.
     *
     * @param n The number of words to include in the list. Must be non-negative.
     * @return An unmodifiable list of words all shuffled up.
     */
    public List<String> randomWords(final int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be non-negative, was " + n);
        }

        final var randomWords = new ArrayList<>(WORDS);
        Collections.shuffle(randomWords, rand);
        return Collections.unmodifiableList(randomWords.subList(0, n));
    }

    /**
     * Generate a pseudo-random string of capital letters of the given length.
     *
     * @param length The length of the string. Must be non-negative
     * @return A random string of capital letters and of the requested length.
     */
    @NonNull
    public String randomString(final int length) {
        return randomString(CAPITALS, length);
    }

    /**
     * Given an alphabet of all possible characters, generate a pseudo-random string of the given
     * length.
     *
     * @param alphabet The alphabet to use. Cannot be null or blank.
     * @param length The length of the string. Must be non-negative
     * @return A random string created by the given alphabet and of the requested length.
     */
    @NonNull
    public String randomString(@NonNull final String alphabet, final int length) {
        return randomString(rand, alphabet, length);
    }

    /**
     * Given an alphabet of all possible characters, and a random number generator, generate a
     * pseudo-random string of the given length.
     *
     * @param generator The random number generator to use
     * @param alphabet The alphabet to use. Cannot be null or blank.
     * @param length The length of the string. Must be non-negative
     * @return A random string created by the given alphabet and of the requested length.
     */
    @NonNull
    // Suppress the warning that we should use assert
    @SuppressWarnings("java:S4274")
    public static String randomString(
            @NonNull final Random generator, @NonNull final String alphabet, final int length) {
        assert !alphabet.isBlank();
        assert length >= 0;

        final var buf = new byte[length];
        for (int i = 0; i < length; i++) {
            buf[i] = (byte) alphabet.charAt(generator.nextInt(alphabet.length()));
        }

        return new String(buf);
    }

    @NonNull
    // Suppress the warning that we should use assert
    @SuppressWarnings("java:S4274")
    public static byte[] randomBytes(@NonNull final Random generator, final int length) {
        assert length >= 0;

        final var data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) generator.nextInt();
        }

        return data;
    }
}
