// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import java.util.Objects;

/** A utility class that deals with logic related to Strings. */
public final class StringUtilities {
    /** Empty {@link String} constant. Non-null, but with no whitespaces. */
    public static final String EMPTY = "";

    /**
     * This method takes an input {@link String} and checks if it is blank.
     * A {@link String} is considered blank if it is either {@code null} or
     * contains only whitespace characters as defined by
     * {@link String#isBlank()}.
     *
     * @param toCheck a {@link String} to check if it is blank
     * @return {@code true} if the given {@link String} to check is either
     * {@code null} or contains only whitespace characters, {@code false}
     * otherwise
     */
    public static boolean isBlank(final String toCheck) {
        return Objects.isNull(toCheck) || toCheck.isBlank();
    }

    private StringUtilities() {}
}
