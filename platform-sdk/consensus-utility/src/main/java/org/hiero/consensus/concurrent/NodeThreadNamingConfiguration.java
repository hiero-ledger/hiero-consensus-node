// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.concurrent.framework.config.CompositeThreadNamingConfiguration;
import org.hiero.consensus.model.node.NodeId;

/**
 * Thread naming scheme made out of component: name from_node to other_node + optional thread number.
 * Please see {@link #generateNextThreadName()} for the details.
 */
public class NodeThreadNamingConfiguration extends CompositeThreadNamingConfiguration {

    /**
     * The ID of the node that is running the thread.
     */
    private NodeId nodeId;

    /**
     * The ID of the other node if this thread is responsible for a task associated with a particular node.
     */
    private NodeId otherNodeId;

    public NodeThreadNamingConfiguration() {}

    /**
     * Set the node ID.
     *
     * @return this object
     */
    @SuppressWarnings("unchecked")
    public NodeThreadNamingConfiguration setNodeId(@NonNull final NodeId nodeId) {
        Objects.requireNonNull(nodeId, "nodeId must not be null");

        this.nodeId = nodeId;
        return this;
    }

    /**
     * Set the node ID of the other node (if created threads will be dealing with a task related to a specific node).
     * Ignored if null.
     *
     * @return this object
     */
    public NodeThreadNamingConfiguration setOtherNodeId(@NonNull final NodeId otherNodeId) {
        Objects.requireNonNull(otherNodeId, "otherNodeId must not be null");

        this.otherNodeId = otherNodeId;
        return this;
    }

    /**
     * <p>
     * Construct a thread name. Format is as follows:
     * </p>
     *
     * <pre>
     *  &lt;COMPONENT: NAME NODE_ID to OTHER_ID #THREAD_NUM&gt;
     *   |________| |__| |________| |______| |_________|
     *       |       |         |        |            |
     *       |   "unnamed"     |        |            |
     *       |    if unset     |  omitted if unset   |
     *       |                 |                     |
     * omitted if unset        |           omitted if unset
     *                         |
     * omitted if both self and other node ID is unset,
     * "? to" if only this node's ID is unset
     * </pre>
     *
     * <p>
     * If the fully formatted thread name has been set, then use that thread name instead of the standard format.
     * </p>
     */
    @Override
    public String generateNextThreadName() {
        // The parts are joined together with a space in-between each.
        final List<String> parts = new LinkedList<>();

        final boolean hasComponent = component != null && !component.isBlank();
        final boolean hasName = threadName != null && !threadName.isBlank();

        if (hasComponent) {
            parts.add(component + ":");
        }

        if (hasName) {
            parts.add(threadName);
        } else {
            parts.add("unnamed");
        }

        if (nodeId != null) {
            parts.add(nodeId.toString());
        }

        if (otherNodeId != null) {
            if (nodeId != null) {
                parts.add("to");
            } else {
                parts.add("? to");
            }
            parts.add(otherNodeId.toString());
        }

        if (useThreadNumbers) {
            parts.add("#" + nextThreadNumber.getAndIncrement());
        }

        final StringBuilder sb = new StringBuilder();
        sb.append("<");
        for (int index = 0; index < parts.size(); index++) {
            sb.append(parts.get(index));
            if (index + 1 < parts.size()) {
                sb.append(" ");
            }
        }
        sb.append(">");

        return sb.toString();
    }
}
