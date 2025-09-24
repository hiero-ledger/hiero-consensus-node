// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
import org.hiero.otter.fixtures.logging.context.NodeLoggingContext;
import org.hiero.otter.fixtures.logging.internal.AbstractInMemoryAppender;
import org.hiero.otter.fixtures.logging.internal.InMemorySubscriptionManager;

/**
 * An {@link Appender} implementation for Log4j2 that provides in-memory storage
 * for log events. This appender is used in testing to capture logs
 * and validate them programmatically.
 *
 * @see AbstractAppender
 */
@SuppressWarnings("unused")
@Plugin(name = "TurtleInMemoryAppender", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE)
public class TurtleInMemoryAppender extends AbstractInMemoryAppender {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final List<StructuredLog> logs = Collections.synchronizedList(new ArrayList<>());

    @Nullable
    private static NodeId parseNodeId(@Nullable final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            final long id = Long.parseLong(value);
            return NodeId.of(id);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /**
     * Constructs an {@code TurtleInMemoryAppender} with the given name.
     *
     * @param name The name of the appender.
     */
    private TurtleInMemoryAppender(@NonNull final String name) {
        super(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void append(@NonNull final LogEvent event) {
        final NodeId nodeId = parseNodeId(event.getContextData().getValue(NodeLoggingContext.NODE_ID_KEY));
        final StructuredLog structuredLog = createStructuredLog(event, nodeId);
        logs.add(structuredLog);
        InMemorySubscriptionManager.INSTANCE.notifySubscribers(structuredLog);
    }

    /**
     * Factory method to create an {@code InMemoryAppender} instance.
     *
     * @param name The name of the appender.
     * @return A new instance of {@code InMemoryAppender}.
     */
    @PluginFactory
    @NonNull
    public static TurtleInMemoryAppender createAppender(@PluginAttribute("name") @NonNull final String name) {
        return new TurtleInMemoryAppender(name);
    }
}
