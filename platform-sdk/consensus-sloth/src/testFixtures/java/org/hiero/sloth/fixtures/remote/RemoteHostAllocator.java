// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.remote;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hiero.consensus.model.node.NodeId;

/**
 * Assigns nodes to remote hosts in round-robin fashion. Multiple nodes can share a single host; each node on the same
 * host receives unique ports derived from a base port plus an offset. An exception is thrown if more nodes are requested
 * than the configured capacity ({@code hosts.size() * nodesPerHost}).
 */
public class RemoteHostAllocator {

    /** Base port for the container control gRPC service. */
    private static final int BASE_CONTROL_PORT = 8080;

    /** Base port for the node communication gRPC service. */
    private static final int BASE_COMM_PORT = 8081;

    /** Base port for gossip communication. */
    private static final int BASE_GOSSIP_PORT = 5777;

    /** Port stride between nodes on the same host (must be >= 2 to cover control + comm). */
    private static final int PORT_STRIDE = 2;

    private final List<String> hosts;
    private final int nodesPerHost;
    private final Map<NodeId, HostAssignment> assignments = new HashMap<>();
    private final Map<String, Integer> nodesOnHost = new HashMap<>();

    /**
     * Creates a new allocator for the given list of hosts.
     *
     * @param hosts the list of SSH host names (must not be empty)
     * @param nodesPerHost maximum number of nodes per host (must be >= 1)
     */
    public RemoteHostAllocator(@NonNull final List<String> hosts, final int nodesPerHost) {
        requireNonNull(hosts, "hosts must not be null");
        if (hosts.isEmpty()) {
            throw new IllegalArgumentException("At least one host must be specified");
        }
        if (nodesPerHost < 1) {
            throw new IllegalArgumentException("nodesPerHost must be >= 1, got " + nodesPerHost);
        }
        this.hosts = List.copyOf(hosts);
        this.nodesPerHost = nodesPerHost;
    }

    /**
     * Allocates a host for the given node. Nodes are distributed across hosts in round-robin order. If multiple nodes
     * land on the same host, each receives unique ports (base port + offset). An {@link IllegalStateException} is
     * thrown when the total capacity ({@code hosts * nodesPerHost}) is exceeded.
     *
     * @param nodeId the node to allocate
     * @return the host assignment with connection details
     * @throws IllegalStateException if all host slots have been exhausted
     */
    @NonNull
    public HostAssignment allocate(@NonNull final NodeId nodeId) {
        if (assignments.containsKey(nodeId)) {
            return assignments.get(nodeId);
        }

        final int totalAllocated = assignments.size();
        final int maxCapacity = hosts.size() * nodesPerHost;
        if (totalAllocated >= maxCapacity) {
            throw new IllegalStateException("Cannot allocate node " + nodeId + ": capacity exhausted (" + hosts.size()
                    + " host(s) x " + nodesPerHost + " node(s)/host = " + maxCapacity + " max).");
        }

        // Round-robin: node 0 -> host 0, node 1 -> host 1, ..., node N -> host N % size
        final String host = hosts.get(totalAllocated % hosts.size());
        final int slotOnHost = nodesOnHost.merge(host, 1, Integer::sum) - 1;

        final HostAssignment assignment = new HostAssignment(
                host,
                BASE_CONTROL_PORT + slotOnHost * PORT_STRIDE,
                BASE_COMM_PORT + slotOnHost * PORT_STRIDE,
                BASE_GOSSIP_PORT + slotOnHost);
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
