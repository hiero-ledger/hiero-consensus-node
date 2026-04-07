// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

/**
 * Assigns each node to a dedicated remote host. Exactly one node per host is supported; an exception is thrown if more
 * nodes are requested than hosts are available.
 */
public class RemoteHostAllocator {

    /** Port for the container control gRPC service. */
    private static final int CONTROL_PORT = 8080;

    /** Port for the node communication gRPC service. */
    private static final int COMM_PORT = 8081;

    /** Port for gossip communication. */
    private static final int GOSSIP_PORT = 5777;

    private final List<String> hosts;
    private final Map<NodeId, HostAssignment> assignments = new HashMap<>();

    /**
     * Creates a new allocator for the given list of hosts.
     *
     * @param hosts the list of SSH host names (must not be empty)
     */
    public RemoteHostAllocator(@NonNull final List<String> hosts) {
        requireNonNull(hosts, "hosts must not be null");
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("At least one host must be specified");
        }
        this.hosts = List.copyOf(hosts);
    }

    /**
     * Allocates a dedicated host for the given node. Each node gets its own host. If more nodes are requested than
     * hosts are available, an {@link IllegalStateException} is thrown.
     *
     * @param nodeId the node to allocate
     * @return the host assignment with connection details
     * @throws IllegalStateException if all hosts have already been allocated
     */
    @NonNull
    public HostAssignment allocate(@NonNull final NodeId nodeId) {
        if (assignments.containsKey(nodeId)) {
            return assignments.get(nodeId);
        }

        final int index = assignments.size();
        if (index >= hosts.size()) {
            throw new IllegalStateException("Cannot allocate node " + nodeId + ": only " + hosts.size()
                    + " host(s) available but " + (index + 1) + " node(s) requested. Each node requires its own host.");
        }

        final String host = hosts.get(index);
        final HostAssignment assignment = new HostAssignment(host, CONTROL_PORT, COMM_PORT, GOSSIP_PORT);
        assignments.put(nodeId, assignment);
        return assignment;
    }

    /**
     * Returns the assignment for a previously allocated node.
     *
     * @param nodeId the node whose assignment to retrieve
     * @return the host assignment
     * @throws IllegalStateException if the node has not been allocated
     */
    @NonNull
    public HostAssignment getAssignment(@NonNull final NodeId nodeId) {
        final HostAssignment assignment = assignments.get(nodeId);
        if (assignment == null) {
            throw new IllegalStateException("Node " + nodeId + " has not been allocated");
        }
        return assignment;
    }

    /**
     * Describes how a node is mapped to a remote host.
     *
     * @param host the SSH host name
     * @param controlPort the container control gRPC port on the remote host
     * @param commPort the node communication gRPC port on the remote host
     * @param gossipPort the gossip port on the remote host
     */
    public record HostAssignment(@NonNull String host, int controlPort, int commPort, int gossipPort) {}
}
