// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.assertions.matchers;

import java.util.List;
import java.util.function.Function;
import org.testcontainers.shaded.org.hamcrest.Matcher;

/**
 * Utility class with methods for creating custom matchers.
 *
 * @author vyanev
 */
public final class MatcherUtils {

    private MatcherUtils() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * @param expected the expected object
     * @param stopClass the class to stop at when comparing fields
     * @return {@link FieldByFieldMatcher} checking if the objects have equal fields
     */
    public static <T> FieldByFieldMatcher<T> withEqualFields(final T expected, final Class<?> stopClass) {
        return new FieldByFieldMatcher<>(expected, stopClass);
    }

    /**
     * @param expectedGas the expected gas
     * @return {@link GasMatcher} checking for equality within a range of 32 units
     */
    public static GasMatcher within64Units(final Long expectedGas) {
        return new GasMatcher(expectedGas, false, 64L);
    }

    /**
     * @param expectedItems list of expected items
     * @param actualItems list of actual items
     * @param matcher the matcher to use for comparison
     * @param <T> the type of the items
     * @return list of items that are in the expected list but not in the actual list
     */
    public static <T> List<T> getMismatchedItems(
            final List<T> expectedItems, final List<T> actualItems, final Function<T, Matcher<T>> matcher) {
        return expectedItems.stream()
                .filter(expectedItem -> actualItems.stream()
                        .noneMatch(actualItem -> matcher.apply(expectedItem).matches(actualItem)))
                .toList();
    }
}
