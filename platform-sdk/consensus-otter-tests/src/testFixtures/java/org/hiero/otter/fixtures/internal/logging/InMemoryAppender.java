// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal.logging;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.hiero.otter.fixtures.turtle.TurtleNode;

/**
 * An {@link Appender} implementation for Log4j2 that provides in-memory storage
 * for log events. This appender is used in testing to capture logs
 * and validate them programmatically.
 *
 * @see AbstractAppender
 */
@Plugin(name = "InMemoryAppender", category = "Core", elementType = Appender.ELEMENT_TYPE)
public class InMemoryAppender extends AbstractAppender {

    private static final List<StructuredLog> logs = Collections.synchronizedList(new ArrayList<>());

    /** No filtering is applied to the log events */
    private static final Filter NO_FILTER = null;

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
    protected InMemoryAppender(@NonNull final String name) {
        super(name, NO_FILTER, DEFAULT_LAYOUT, PROPAGATE_EXCEPTIONS, NO_PROPERTIES);
    }

    /**
     * Appends a log event to the in-memory store.
     *
     * @param event The log event to be appended.
     */
    @Override
    public void append(@NonNull final LogEvent event) {
        final StructuredLog log = new StructuredLog(
                event.getLevel(),
                event.getMessage().getFormattedMessage(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getMarker(),
                event.getContextData().getValue(TurtleNode.THREAD_CONTEXT_NODE_ID));
        logs.add(log);
    }

    /**
     * Returns an unmodifiable list of all captured log statements
     *
     * @return an unmodifiable list of all captured log statements
     */
    @NonNull
    public static List<StructuredLog> getLogs() {
        return Collections.unmodifiableList(logs);
    }

    /**
     * Clears all logs currently stored in the in-memory appender.
     */
    public static void clearLogs() {
        logs.clear();
    }

    /**
     * Factory method to create an {@code InMemoryAppender} instance.
     *
     * @param name The name of the appender.
     * @return A new instance of {@code InMemoryAppender}.
     */
    @PluginFactory
    @NonNull
    public static InMemoryAppender createAppender(@PluginAttribute("name") @NonNull final String name) {
        return new InMemoryAppender(name);
    }
}
