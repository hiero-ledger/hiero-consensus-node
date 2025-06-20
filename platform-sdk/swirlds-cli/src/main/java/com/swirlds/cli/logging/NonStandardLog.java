// SPDX-License-Identifier: Apache-2.0
package com.swirlds.cli.logging;

import static com.swirlds.cli.logging.HtmlGenerator.BLACKLIST_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.ELAPSED_TIME_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.HIDEABLE_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.LOG_NUMBER_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.NODE_ID_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.NON_STANDARD_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.TIMESTAMP_COLUMN_LABEL;
import static com.swirlds.cli.logging.HtmlGenerator.WHITELIST_LABEL;
import static com.swirlds.cli.logging.LogProcessingUtils.escapeString;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Objects;

/**
 * A log that doesn't adhere to the standard format. Becomes part of the most recent log line.
 */
public class NonStandardLog {
    /**
     * The original text
     */
    private String nonStandardText = "";

    public void addLogText(@NonNull final String logText) {
        if (!nonStandardText.isEmpty()) {
            this.nonStandardText += "\n";
        }
        this.nonStandardText += Objects.requireNonNull(logText);
    }

    @NonNull
    public String generateHtmlString() {
        final StringBuilder stringBuilder = new StringBuilder();

        // add some empty cells for alignment purposes
        stringBuilder
                .append(new HtmlTagFactory("td", "")
                        .addClasses(List.of(NODE_ID_COLUMN_LABEL, HIDEABLE_LABEL))
                        .addAttribute(BLACKLIST_LABEL, "0")
                        .addAttribute(WHITELIST_LABEL, "0")
                        .generateTag())
                .append("\n");
        stringBuilder
                .append(new HtmlTagFactory("td", "")
                        .addClasses(List.of(ELAPSED_TIME_COLUMN_LABEL, HIDEABLE_LABEL))
                        .addAttribute(BLACKLIST_LABEL, "0")
                        .addAttribute(WHITELIST_LABEL, "0")
                        .generateTag())
                .append("\n");
        stringBuilder
                .append(new HtmlTagFactory("td", "")
                        .addClasses(List.of(TIMESTAMP_COLUMN_LABEL, HIDEABLE_LABEL))
                        .addAttribute(BLACKLIST_LABEL, "0")
                        .addAttribute(WHITELIST_LABEL, "0")
                        .generateTag())
                .append("\n");
        stringBuilder
                .append(new HtmlTagFactory("td", "")
                        .addClasses(List.of(LOG_NUMBER_COLUMN_LABEL, HIDEABLE_LABEL))
                        .addAttribute(BLACKLIST_LABEL, "0")
                        .addAttribute(WHITELIST_LABEL, "0")
                        .generateTag())
                .append("\n");

        // add the non-standard contents
        stringBuilder
                .append(new HtmlTagFactory("td", escapeString(nonStandardText))
                        .addClasses(List.of(NON_STANDARD_LABEL, HIDEABLE_LABEL))
                        .addAttribute("colspan", "6")
                        .addAttribute(BLACKLIST_LABEL, "0")
                        .addAttribute(WHITELIST_LABEL, "0")
                        .generateTag())
                .append("\n");

        return new HtmlTagFactory("tr", stringBuilder.toString()).generateTag();
    }
}
