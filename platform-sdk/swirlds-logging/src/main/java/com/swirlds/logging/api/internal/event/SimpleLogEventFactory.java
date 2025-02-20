// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.event;

import com.swirlds.base.context.internal.GlobalContext;
import com.swirlds.base.context.internal.ThreadLocalContext;
import com.swirlds.logging.api.Level;
import com.swirlds.logging.api.Marker;
import com.swirlds.logging.api.extensions.event.LogEvent;
import com.swirlds.logging.api.extensions.event.LogEventFactory;
import com.swirlds.logging.api.extensions.event.LogMessage;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * A log event factory that simply creates a new log event instance for every log statement.
 */
public class SimpleLogEventFactory implements LogEventFactory {

    @NonNull
    @Override
    public LogEvent createLogEvent(
            @NonNull Level level,
            @NonNull String loggerName,
            @NonNull String threadName,
            long timestamp,
            @NonNull LogMessage message,
            @Nullable Throwable throwable,
            @Nullable Marker marker,
            @Nullable Map<String, String> context) {
        final Map<String, String> mergedContext = new HashMap<>();
        if (context != null) {
            mergedContext.putAll(context);
        }
        mergedContext.putAll(GlobalContext.getContextMap());
        mergedContext.putAll(ThreadLocalContext.getContextMap());
        return new DefaultLogEvent(level, loggerName, threadName, timestamp, message, throwable, marker, mergedContext);
    }
}
