// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging.internal;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Filter.Result;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.filter.LevelRangeFilter;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.hiero.otter.fixtures.logging.StructuredLog;

/**
 * An abstract base class for in-memory appenders that store log events in memory.
 * This class provides a foundation for creating specific in-memory appenders
 * that can be used in testing or other scenarios where log events need to be captured.
 */
public abstract class AbstractInMemoryAppender extends AbstractAppender {

    /** No filtering is applied to the log events */
    private static final Filter INFO_LEVEL_FILTER =
            LevelRangeFilter.createFilter(null, Level.INFO, Result.NEUTRAL, Result.DENY);

    /**
     * The default layout is used to format log events.
     * Although formatting is not relevant for in-memory storage,
     * Log4j requires a layout to be specified.
     */
    private static final PatternLayout DEFAULT_LAYOUT = PatternLayout.createDefaultLayout();

    /** Propagate exceptions from the logging system */
    private static final boolean PROPAGATE_EXCEPTIONS = false;

    /** No Additional Properties */
    private static final Property[] NO_PROPERTIES = Property.EMPTY_ARRAY;

    /**
     * Constructs an {@code InMemoryAppender} with the given name.
     *
     * @param name The name of the appender.
     */
    protected AbstractInMemoryAppender(@NonNull final String name) {
        super(name, INFO_LEVEL_FILTER, DEFAULT_LAYOUT, PROPAGATE_EXCEPTIONS, NO_PROPERTIES);
    }

    /**
     * Creates a log event to the in-memory store.
     *
     * @param event The log event subscribers will be notified about.
     * @param nodeId The nodeId associated with the log event.
     * @return The structured log entry created from the event.
     */
    protected StructuredLog createStructuredLog(@NonNull final LogEvent event, @Nullable final NodeId nodeId) {
        return new StructuredLog(
                event.getTimeMillis(),
                event.getLevel(),
                event.getMessage().getFormattedMessage(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getMarker(),
                nodeId);
    }
}
