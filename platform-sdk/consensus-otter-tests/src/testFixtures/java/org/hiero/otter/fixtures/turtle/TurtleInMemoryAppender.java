// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Node;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.logging.StructuredLog;
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

    static {
        objectMapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Converts a {@link NodeId} to a string representation suitable for thread context.
     *
     * @param nodeId the {@link NodeId} to convert
     * @return a {@code String} representation of the {@link NodeId} in JSON format, or {@code null} if the input is {@code null}
     */
    @Nullable
    public static String toJSON(@Nullable final NodeId nodeId) {
        Objects.requireNonNull(nodeId);
        try {
            return objectMapper.writeValueAsString(nodeId);
        } catch (final JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private static com.hedera.hapi.platform.state.NodeId toProto(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId);
        return com.hedera.hapi.platform.state.NodeId.newBuilder()
                .id(nodeId.id())
                .build();
    }

    /**
     * Parses a string representation of a {@link NodeId} from the thread context.
     *
     * @param value the string representation of the {@link NodeId}
     * @return a {@link NodeId} object if parsing is successful, or {@code null} if the input is {@code null} or empty
     */
    @Nullable
    public static NodeId fromJSON(@Nullable final String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(value, NodeId.class);
        } catch (final JsonProcessingException e) {
            throw new RuntimeException(e);
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
        final NodeId nodeId = fromJSON(event.getContextData().getValue(TurtleNode.THREAD_CONTEXT_NODE_ID));
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
