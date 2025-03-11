// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.event;

import com.swirlds.logging.api.extensions.provider.LogProvider;
import com.swirlds.logging.api.internal.event.ParameterizedLogMessage;
import com.swirlds.logging.api.internal.event.SimpleLogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A log message that is part of a {@link LogEvent}. A message can be a simple String (see {@link SimpleLogMessage}) or
 * a parameterized String (see {@link ParameterizedLogMessage}). {@link LogProvider} can provide custom implementations of this interface.
 *
 * @see SimpleLogMessage
 * @see ParameterizedLogMessage
 */
public interface LogMessage {

    /**
     * Returns the message as a String. If the message is parameterized, the parameters are resolved.
     *
     * @return the message as a String
     */
    @NonNull
    String getMessage();
}
