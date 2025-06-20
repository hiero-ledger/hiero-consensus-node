// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy.json;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A filter that operates on exceptions.
 */
public class HasExceptionFilter implements Predicate<JsonLogEntry> {

    private final Set<String> exceptionTypes;

    public static HasExceptionFilter hasException(final String... exceptionTypes) {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, exceptionTypes);
        return new HasExceptionFilter(set);
    }

    public static HasExceptionFilter hasException(final List<String> exceptionTypes) {
        return new HasExceptionFilter(new HashSet<>(exceptionTypes));
    }

    public static HasExceptionFilter hasException(final Set<String> exceptionTypes) {
        return new HasExceptionFilter(exceptionTypes);
    }

    /**
     * Create a filter that catches only specific exception types.
     *
     * @param exceptionTypes
     * 		a list of exception type names. Exact matches only, does not consider inheritance.
     */
    public HasExceptionFilter(final Set<String> exceptionTypes) {
        this.exceptionTypes = exceptionTypes;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean test(final JsonLogEntry entry) {
        if (!entry.hasException() || exceptionTypes == null) {
            return false;
        }
        return exceptionTypes.contains(entry.getExceptionType());
    }
}
