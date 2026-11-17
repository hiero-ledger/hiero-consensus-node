// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.junit;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Named;

/**
 * The arguments of a single invocation of a test method, as provided by one element of an argument source.
 *
 * @param values the values that are bound to the leading parameters of the test method, may contain {@code null}
 * @param label a human-readable description of the values, used in the display name of the invocation
 */
record ArgumentSet(@NonNull List<Object> values, @NonNull String label) {

    /** The argument set of a test method that has no argument source. */
    static final ArgumentSet EMPTY = new ArgumentSet(List.of(), "");

    /**
     * Constructor for {@link ArgumentSet}.
     *
     * @param values the values that are bound to the leading parameters of the test method, may contain {@code null}
     * @param label a human-readable description of the values, used in the display name of the invocation
     */
    ArgumentSet {
        // List.copyOf() would reject null values
        values = Collections.unmodifiableList(new ArrayList<>(values));
        requireNonNull(label);
    }

    /**
     * Creates an argument set from the raw arguments of an argument source. A {@link Named} argument contributes its
     * payload as value and its name to the label.
     *
     * @param arguments the raw arguments
     * @return the argument set
     */
    @NonNull
    static ArgumentSet of(@NonNull final Object... arguments) {
        final List<Object> values = Arrays.stream(arguments)
                .map(argument -> argument instanceof final Named<?> named ? named.getPayload() : argument)
                .toList();
        final String label = Arrays.stream(arguments)
                .map(argument -> argument instanceof final Named<?> named ? named.getName() : String.valueOf(argument))
                .collect(Collectors.joining(", "));
        return new ArgumentSet(values, label);
    }

    /**
     * Checks whether this argument set has no values, which is the case for a test method without an argument source.
     *
     * @return {@code true} if this argument set has no values
     */
    boolean isEmpty() {
        return values.isEmpty();
    }
}
