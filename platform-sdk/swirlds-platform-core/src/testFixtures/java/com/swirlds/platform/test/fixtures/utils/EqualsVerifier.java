// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.hiero.base.crypto.DigestType;
import org.hiero.base.crypto.Hash;

public final class EqualsVerifier {

    // Do not instantiate
    private EqualsVerifier() {}

    public static Hash randomHash(final RandomGenerator r) {
        final int SIZE = 48;
        byte[] value = new byte[SIZE];
        r.nextBytes(value);
        return new Hash(value, DigestType.SHA_384);
    }

    private static final Random random = new Random();

    public static <R> List<R> generateObjects(final Function<Random, R> supplier, final long[] seeds) {
        final ArrayList<R> objects = new ArrayList<>();
        for (final long seed : seeds) {
            random.setSeed(seed);
            objects.add(supplier.apply(random));
        }
        return objects;
    }

    /**
     * Verifies equals() and hashCode()
     *
     * @param original
     * 		an instance of type R
     * @param copy
     * 		an equal instance of type R
     * @param other
     * 		a different instance of type R
     * @param <R>
     * 		arbitrary type
     * @return true if all checks pass
     */
    public static <R> boolean verifyEqualsHashCode(final R original, final R copy, final R other) { // NOSONAR

        // Not equal null
        if (original.equals(null)) { // NOSONAR
            return false;
        }

        // Reflexive
        if (!original.equals(original)) { // NOSONAR
            return false;
        }

        // Symmetric
        if (!original.equals(copy)) {
            return false;
        }
        if (!copy.equals(original)) { // NOSONAR
            return false;
        }

        // Falsifiable
        if (original.equals(other)) {
            return false;
        }

        // Consistent
        if (copy.equals(other)) { // NOSONAR
            return false;
        }

        // Stable
        if (!original.equals(copy)) { // NOSONAR
            return false;
        }

        /*
         * hashCode() tests
         */

        // Consistent with equals()
        if (original.hashCode() != copy.hashCode()) {
            return false;
        }

        // Random instance hash codes shouldn't match
        if (original.hashCode() == other.hashCode()) { // NOSONAR
            return false;
        }

        return true;
    }

    /**
     * Verifies if equals()/hashCode() implemented by type R fulfill the contracts
     *
     * @param supplier
     * 		predefined supplier of random instances of type R
     * @param <R>
     * 		arbitrary type
     * @return true if all checks pass
     */
    public static <R> boolean verify(final Function<Random, R> supplier) {
        final List<R> list = generateObjects(supplier, new long[] {1, 1, 2}); // NOSONAR
        return verifyEqualsHashCode(list.get(0), list.get(1), list.get(2)); // NOSONAR
    }

    /**
     * Verifies compareTo()
     *
     * @param original
     * 		an instance of type R
     * @param copy
     * 		an equal instance of type R
     * @param other
     * 		a different instance of type R
     * @param <R>
     * 		arbitrary type implementing Comparable
     * @return true if all checks pass
     */
    public static <R extends Comparable<R>> boolean verifyCompareTo(
            final R original, final R copy, final R other) { // NOSONAR
        // Reflexive
        if (original.compareTo(original) != 0) {
            return false;
        }

        // Consistent with equals()
        if (original.compareTo(copy) != 0) {
            return false;
        }

        // Symmetrical equality
        if (copy.compareTo(original) != 0) {
            return false;
        }

        // Asymmetrical inequality
        if (original.compareTo(other) * other.compareTo(original) >= 0) { // NOSONAR
            return false;
        }

        return true;
    }
}
