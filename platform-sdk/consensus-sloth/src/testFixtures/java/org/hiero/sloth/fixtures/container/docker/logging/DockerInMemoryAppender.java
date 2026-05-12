// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.container.docker.logging;

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
import org.hiero.sloth.fixtures.logging.StructuredLog;
import org.hiero.sloth.fixtures.logging.internal.AbstractInMemoryAppender;
import org.hiero.sloth.fixtures.logging.internal.InMemorySubscriptionManager;

/**
 * An {@link Appender} implementation for Log4j2 that provides in-memory storage
 * for log events.
 *
 * @see AbstractAppender
 */
@SuppressWarnings("unused")
@Plugin(name = "DockerInMemoryAppender", category = Node.CATEGORY, elementType = Appender.ELEMENT_TYPE)
public class DockerInMemoryAppender extends AbstractInMemoryAppender {

    @Nullable
    private final NodeId nodeId;

    private DockerInMemoryAppender(@NonNull final String name, @Nullable final NodeId nodeId) {
        super(name);
        this.nodeId = nodeId;
    }

    /** {@inheritDoc} */
    @Override
    public void append(final LogEvent event) {
        final StructuredLog structuredLog = createStructuredLog(event, nodeId);
        InMemorySubscriptionManager.INSTANCE.notifySubscribers(structuredLog);
    }

    /**
     * Factory method to create an {@code DockerInMemoryAppender} instance.
     *
     * @param name The name of the appender.
     * @param nodeId The node ID associated with this appender.
     * @return A new instance of {@code DockerInMemoryAppender}.
     */
    @PluginFactory
    @NonNull
    public static DockerInMemoryAppender createAppender(
            @PluginAttribute("name") @NonNull final String name, @PluginAttribute("nodeId") final long nodeId) {
        return new DockerInMemoryAppender(name, nodeId < 0 ? null : NodeId.of(nodeId));
    }
}
