// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.legacy;

/**
 * A collection of utilities to assist with logging.
 */
public final class LoggingUtils {

    private LoggingUtils() {}

    /**
     * Choose between a singular and plural word depending on a count.
     *
     * @param count
     * 		the count of objects
     * @param singular
     * 		the singular version of the word, e.g. "goose"
     * @param plural
     * 		the plural version of the word, e.g. "geese"
     * @return the correct form of the word given the count
     */
    public static String plural(final long count, final String singular, final String plural) {
        if (count == 1) {
            return singular;
        }
        return plural;
    }

    /**
     * Choose between a singular and plural word depending on the count.
     * For words that can be made plural by adding an "s" to the end.
     *
     * @param count
     * 		the count of objects
     * @param singular
     * 		the singular version of the word, e.g. "dog"
     * @return the correct form of the word given the count
     */
    public static String plural(final long count, final String singular) {
        if (count == 1) {
            return singular;
        }
        return singular + "s";
    }
}
