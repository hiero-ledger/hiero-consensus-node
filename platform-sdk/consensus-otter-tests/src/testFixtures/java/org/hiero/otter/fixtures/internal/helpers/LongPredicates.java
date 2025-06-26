// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.helpers;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.function.BiPredicate;

public class LongPredicates {

    private LongPredicates() {
        // Prevent instantiation
    }

    /**
     * A functional interface that represents a predicate taking two long values.
     */
    public record LongBiPredicate(@NonNull String operationName, @NonNull BiPredicate<Long, Long> predicate)
            implements BiPredicate<Long, Long> {
        @Override
        public boolean test(final Long value1, final Long value2) {
            return predicate.test(value1, value2);
        }
    }

    /**
     * Predicate that checks if the first value is greater than the second value.
     */
    public static final LongBiPredicate IS_GREATER_THAN =
            new LongBiPredicate("greater than", (value1, value2) -> value1 > value2);

    /**
     * Predicate that checks if the first value is greater than or equal to the second value.
     */
    public static final LongBiPredicate IS_GREATER_THAN_OR_EQUAL_TO =
            new LongBiPredicate("greater than or equal to", (value1, value2) -> value1 >= value2);

    /**
     * Predicate that checks if the first value is less than the second value.
     */
    public static final LongBiPredicate IS_LESS_THAN =
            new LongBiPredicate("less than", (value1, value2) -> value1 < value2);

    /**
     * Predicate that checks if the first value is less than or equal to the second value.
     */
    public static final LongBiPredicate IS_LESS_THAN_OR_EQUAL_TO =
            new LongBiPredicate("less than or equal to", (value1, value2) -> value1 <= value2);

    /**
     * Predicate that checks if the first value is equal to the second value.
     */
    public static final LongBiPredicate IS_EQUAL_TO = new LongBiPredicate("equal to", Objects::equals);
}
