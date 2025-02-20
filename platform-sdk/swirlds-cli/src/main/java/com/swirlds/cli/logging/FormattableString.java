// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A string that can be formatted
 */
public interface FormattableString {
    /**
     * Gets the original plaintext version of the string
     *
     * @return the plain text string
     */
    @NonNull
    String getOriginalPlaintext();

    /**
     * Generate a string with ANSI coloration
     *
     * @return the string with ANSI coloration
     */
    @NonNull
    String generateAnsiString();

    /**
     * Generate a string with HTML formatting
     * <p>
     * This method must return a string that is safe to be inserted into an HTML document
     *
     * @return the string with HTML formatting
     */
    @NonNull
    String generateHtmlString();
}
