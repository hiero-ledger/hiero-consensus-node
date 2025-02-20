// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Class that contains common checks like null checks as static methods.
 */
public final class ArgumentUtils {

    public static final String ERROR_ARGUMENT_NULL = "The supplied argument '%s' cannot be null!";
    public static final String ERROR_ARGUMENT_BLANK = "The supplied argument '%s' cannot be blank!";

    /**
     * Private constructor to prevent instantiation.
     */
    private ArgumentUtils() throws IllegalAccessException {
        throw new IllegalAccessException("Cannot instantiate utility class!");
    }

    /**
     * Throw an {@link IllegalArgumentException} if the supplied {@code String} is blank. Throw an
     * {@link NullPointerException} if the supplied {@code String} is {@code null}.
     *
     * @param argument     the argument checked
     * @param argumentName the name of the argument
     * @see String#isBlank()
     */
    @NonNull
    public static String throwArgBlank(@NonNull final String argument, @NonNull final String argumentName)
            throws NullPointerException, IllegalArgumentException {
        if (argument == null) {
            throw new NullPointerException(ERROR_ARGUMENT_NULL.formatted(argumentName));
        }
        if (argument.isBlank()) {
            throw new IllegalArgumentException(ERROR_ARGUMENT_BLANK.formatted(argumentName));
        }
        return argument;
    }
}
