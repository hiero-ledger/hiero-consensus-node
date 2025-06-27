// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.logging.internal;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.state.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.result.LogSubscriber;
import org.hiero.otter.fixtures.result.SubscriberAction;
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
    private static final List<LogSubscriber> subscribers = new CopyOnWriteArrayList<>();

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
     * Subscribes a {@link LogSubscriber} to receive log events.
     *
     * @param subscriber The subscriber to be added.
     */
    public static void subscribe(@NonNull final LogSubscriber subscriber) {
        requireNonNull(subscriber);
        subscribers.add(subscriber);
    }

    /**
     * Appends a log event to the in-memory store.
     *
     * @param event The log event to be appended.
     */
    @Override
    public void append(@NonNull final LogEvent event) {
        final NodeId nodeId = convertSafelyToNodeId(event.getContextData().getValue(TurtleNode.THREAD_CONTEXT_NODE_ID));
        final StructuredLog log = new StructuredLog(
                event.getTimeMillis(),
                event.getLevel(),
                event.getMessage().getFormattedMessage(),
                event.getLoggerName(),
                event.getThreadName(),
                event.getMarker(),
                nodeId);
        logs.add(log);

        subscribers.removeIf(subscriber -> subscriber.onLogEntry(log) == SubscriberAction.UNSUBSCRIBE);
    }

    @Nullable
    private static NodeId convertSafelyToNodeId(@Nullable final String value) {
        if (value == null) {
            return null;
        }
        try {
            return NodeId.newBuilder().id(Long.parseLong(value)).build();
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Returns an unmodifiable list of all captured log statements for a specific node
     *
     * @param nodeId nodeId of the node
     *
     * @return an unmodifiable list of all captured log statements for {@code nodeId}
     */
    @NonNull
    public static List<StructuredLog> getLogs(@NonNull final NodeId nodeId) {
        synchronized (logs) {
            return logs.stream()
                    .filter(Objects::nonNull)
                    .filter(log -> log.nodeId() == nodeId)
                    .toList();
        }
    }

    /**
     * Returns an unmodifiable list of all captured log statements for all nodes
     *
     * @return an unmodifiable list of all captured log statements
     */
    @NonNull
    public static List<StructuredLog> getLogs() {
        synchronized (logs) {
            return logs.stream().filter(Objects::nonNull).toList();
        }
    }

    /**
     * Resets the {@link InMemoryAppender} by clearing all logs and unregistering all subscribers.
     */
    public static void reset() {
        subscribers.clear();
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
