// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.log4j.factory;

import com.swirlds.logging.api.extensions.event.LogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.message.Message;

/**
 * Wraps a Log4J message to be used as a LogMessage in swirlds-logging.
 *
 * @param message the Log4J message
 */
public record Log4JMessage(@NonNull Message message) implements LogMessage {
    /**
     * Formats the message if the message is used by the swirlds-logging API.
     *
     * @return formatted message
     */
    @NonNull
    @Override
    public String getMessage() {
        return message.getFormattedMessage();
    }
}
