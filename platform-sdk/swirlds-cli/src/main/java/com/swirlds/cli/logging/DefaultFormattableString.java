// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Default implementation of {@link FormattableString}. Doesn't do any formatting.
 */
public class DefaultFormattableString implements FormattableString {
    /**
     * The original string
     */
    private final String originalString;

    /**
     * Constructor
     *
     * @param inputString the input string
     */
    public DefaultFormattableString(@NonNull final String inputString) {
        originalString = inputString;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getOriginalPlaintext() {
        return originalString;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String generateAnsiString() {
        return originalString;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String generateHtmlString() {
        return LogProcessingUtils.escapeString(originalString);
    }
}
