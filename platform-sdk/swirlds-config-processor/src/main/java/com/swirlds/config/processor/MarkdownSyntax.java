// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.processor;

public final class MarkdownSyntax {

    public static final String H2_PREFIX = "## ";
    public static final String BOLD_MARKER = "**";

    public static final String CODE_MARKER = "`";

    public static final String NEWLINE = "\n\n";

    public static final String DEFAULT_VALUE = BOLD_MARKER + "default value:" + BOLD_MARKER + " ";

    public static final String DESCRIPTION = BOLD_MARKER + "description:" + BOLD_MARKER + " ";

    public static final String RECORD = BOLD_MARKER + "record:" + BOLD_MARKER + " ";

    public static final String TYPE = BOLD_MARKER + "type:" + BOLD_MARKER + " ";
    public static final String NO_DEFAULT_VALUE = BOLD_MARKER + "no default value" + BOLD_MARKER;
    public static final String DEFAULT_VALUE_IS_NULL =
            BOLD_MARKER + "default value is " + CODE_MARKER + "null" + CODE_MARKER + BOLD_MARKER;

    /**
     * Private constructor to prevent instantiation.
     */
    private MarkdownSyntax() {}

    /**
     * Returns the given text as code in markdown.
     *
     * @param text The text to return as code in markdown.
     * @return The given text as code in markdown.
     */
    public static String asCode(final String text) {
        return CODE_MARKER + text + CODE_MARKER;
    }
}
