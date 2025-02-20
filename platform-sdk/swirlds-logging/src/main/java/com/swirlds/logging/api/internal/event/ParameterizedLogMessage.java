// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.event;

import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.extensions.emergency.EmergencyLoggerProvider;
import com.swirlds.logging.api.extensions.event.LogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A log message that can be parameterized with arguments. The arguments are inserted into the message at the positions
 * of the {} placeholders.
 * <p>
 * The implementation is copied from slf4j for first tests. need to be replaced in future. SLF4J is using MIT license
 * (<a href="https://github.com/qos-ch/slf4j/blob/master/LICENSE.txt">...</a>). Based on that we can use it in our project for now
 *
 * @see LogMessage
 */
public class ParameterizedLogMessage implements LogMessage {

    private static final char DELIM_START = '{';
    private static final String DELIM_STR = "{}";
    private static final char ESCAPE_CHAR = '\\';
    private static final String BROKEN_MESSAGE = "BROKEN-MESSAGE";
    private final String messagePattern;

    private final Object[] args;

    private volatile String message = null;

    /**
     * @param messagePattern the message pattern
     * @param args           the arguments
     */
    public ParameterizedLogMessage(final @NonNull String messagePattern, final @Nullable Object... args) {
        this.messagePattern = messagePattern;
        this.args = args;
    }

    @NonNull
    @Override
    public String getMessage() {
        if (message == null) {
            message = createMessage();
        }
        return message;
    }

    private @NonNull String createMessage() {
        try {
            if (messagePattern == null) {
                return "";
            }

            if (args == null) {
                return messagePattern;
            }

            int i = 0;
            int j;
            // use string builder for better multicore performance
            final StringBuilder sbuf = new StringBuilder(messagePattern.length() + 50);

            int L;
            for (L = 0; L < args.length; L++) {

                j = messagePattern.indexOf(DELIM_STR, i);

                if (j == -1) {
                    // no more variables
                    if (i == 0) { // this is a simple string
                        return messagePattern;
                    } else { // add the tail string which contains no variables and return
                        // the result.
                        sbuf.append(messagePattern, i, messagePattern.length());
                        return sbuf.toString();
                    }
                } else {
                    if (isEscapedDelimiter(messagePattern, j)) {
                        if (!isDoubleEscaped(messagePattern, j)) {
                            L--; // DELIM_START was escaped, thus should not be incremented
                            sbuf.append(messagePattern, i, j - 1);
                            sbuf.append(DELIM_START);
                            i = j + 1;
                        } else {
                            // The escape character preceding the delimiter start is
                            // itself escaped: "abc x:\\{}"
                            // we have to consume one backward slash
                            sbuf.append(messagePattern, i, j - 1);
                            deeplyAppendParameter(sbuf, args[L]);
                            i = j + 2;
                        }
                    } else {
                        // normal case
                        sbuf.append(messagePattern, i, j);
                        deeplyAppendParameter(sbuf, args[L]);
                        i = j + 2;
                    }
                }
            }
            // append the characters following the last {} pair.
            sbuf.append(messagePattern, i, messagePattern.length());
            return sbuf.toString();
        } catch (final Throwable e) {
            EmergencyLoggerProvider.getEmergencyLogger().log(Level.ERROR, "Failed to format message", e);
            return BROKEN_MESSAGE;
        }
    }

    private static boolean isEscapedDelimiter(@NonNull final String messagePattern, final int delimiterStartIndex) {

        if (delimiterStartIndex == 0) {
            return false;
        }
        char potentialEscape = messagePattern.charAt(delimiterStartIndex - 1);
        return potentialEscape == ESCAPE_CHAR;
    }

    private static boolean isDoubleEscaped(@NonNull final String messagePattern, int delimiterStartIndex) {
        return delimiterStartIndex >= 2 && messagePattern.charAt(delimiterStartIndex - 2) == ESCAPE_CHAR;
    }

    private static void deeplyAppendParameter(@NonNull final StringBuilder sbuf, @Nullable final Object o) {
        if (o == null) {
            sbuf.append("null");
            return;
        }
        try {
            final String oAsString = o.toString();
            sbuf.append(oAsString);
        } catch (Throwable t) {
            sbuf.append("[FAILED toString()]");
        }
    }
}
