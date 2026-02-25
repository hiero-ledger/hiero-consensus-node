// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.otter.docker.app.logging;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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
@Plugin(name = "DockerInMemoryAppender", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE)
public class DockerInMemoryAppender extends AbstractInMemoryAppender {

    @Nullable
    private final NodeId nodeId;

    /**
     * Constructs an {@code DockerInMemoryAppender} with the given name.
     *
     * @param name The name of the appender.
     */
    private DockerInMemoryAppender(@NonNull final String name, @Nullable final NodeId nodeId) {
        super(name);
        this.nodeId = nodeId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void append(final LogEvent event) {
        final StructuredLog structuredLog = createStructuredLog(event, nodeId);
        InMemorySubscriptionManager.INSTANCE.notifySubscribers(structuredLog);
    }

    /**
     * Factory method to create an {@code InMemoryAppender} instance.
     *
     * @param name The name of the appender.
     * @param nodeId The node ID associated with this appender.
     * @return A new instance of {@code InMemoryAppender}.
     */
    @PluginFactory
    @NonNull
    public static DockerInMemoryAppender createAppender(
            @PluginAttribute("name") @NonNull final String name, @PluginAttribute("nodeId") final long nodeId) {
        return new DockerInMemoryAppender(name, nodeId < 0 ? null : NodeId.of(nodeId));
    }
}
