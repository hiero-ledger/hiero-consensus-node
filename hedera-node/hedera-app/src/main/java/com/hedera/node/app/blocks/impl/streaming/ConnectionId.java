// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a connection between a consensus node and a block node. Each connection is compromised of three components:
 * <ul>
 *     <li>Node ID: the self node ID of this consensus node</li>
 *     <li>Connection Type: the type of connection this represents (i.e. a streaming connection or service connection)</li>
 *     <li>Sequence Number: A unique number assigned to the connection that is unique to the connection type for the
 *     lifespan of the JVM</li>
 * </ul>
 */
public class ConnectionId {
    public enum ConnectionType {
        /**
         * Denotes a connection that intends to stream block data to a block node.
         */
        BLOCK_STREAMING("STR"), // block STReaming
        /**
         * Denotes a connection that intends to query server information from a block node.
         */
        SERVER_STATUS("SVC"); // block node SerViCe

        private final String key;

        ConnectionType(final String key) {
            this.key = key;
        }
    }

    /**
     * Connection ID counters for each type of connection.
     */
    private static final Map<ConnectionType, AtomicInteger> connIdCtrByType = new EnumMap<>(ConnectionType.class);

    static {
        for (final ConnectionType type : ConnectionType.values()) {
            connIdCtrByType.put(type, new AtomicInteger(0));
        }
    }

    private final long nodeId;
    private final ConnectionType type;
    private final int sequenceNumber;
    private final String asString;

    /**
     * Create a new connection ID with fixed values. For production code, you should create a new connection ID by
     * calling {@link #newConnectionId(long, ConnectionType)}.
     *
     * @param nodeId the self node ID of this consensus node
     * @param type the type of connection
     * @param sequenceNumber the unique sequence number
     */
    ConnectionId(final long nodeId, @NonNull final ConnectionType type, final int sequenceNumber) {
        this.nodeId = nodeId;
        this.type = requireNonNull(type, "Type is required");
        this.sequenceNumber = sequenceNumber;

        asString = "N" + nodeId + "-" + type.key + sequenceNumber;
    }

    public long nodeId() {
        return nodeId;
    }

    public ConnectionType type() {
        return type;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    @Override
    public @NonNull String toString() {
        return asString;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ConnectionId that = (ConnectionId) o;
        return nodeId == that.nodeId && sequenceNumber == that.sequenceNumber && type == that.type;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, type, sequenceNumber);
    }

    /**
     * Create a new connection ID for the specified connection type and node.
     *
     * @param nodeId the local node ID
     * @param type the type of connection being created
     * @return the new connection ID
     */
    public static ConnectionId newConnectionId(final long nodeId, @NonNull final ConnectionType type) {
        final int id = connIdCtrByType.get(type).incrementAndGet();
        return new ConnectionId(nodeId, type, id);
    }
}
