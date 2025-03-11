// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.event;

import com.swirlds.logging.api.extensions.event.LogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A simple log message that is just a String that does not need to be handled / parsed / ... in any specific way.
 *
 * @param message The message
 * @see LogMessage
 */
public record SimpleLogMessage(@NonNull String message) implements LogMessage {

    @Override
    public String getMessage() {
        return message;
    }
}
