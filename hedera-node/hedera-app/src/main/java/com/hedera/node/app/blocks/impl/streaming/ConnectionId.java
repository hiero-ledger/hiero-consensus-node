// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Logger logger = LogManager.getLogger(ConnectionId.class);

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
     * Number of base-36 characters in the JVM instance token.
     */
    static final int JVM_ID_LENGTH = 8;

    /**
     * Exclusive upper bound of the random value encoded in the JVM instance token (36^8, about 2.8 trillion values).
     */
    static final long JVM_ID_BOUND = 2_821_109_907_456L;

    /**
     * Random token that identifies this JVM instance, made of exactly {@link #JVM_ID_LENGTH} base-36 characters. Empty
     * if the token could not be created.
     */
    private static final String JVM_ID = buildJvmId(SecureRandom::new);

    /**
     * Suffix appended to the end of every correlation ID, in the format of {@code -JVM<token>}. Empty if the JVM
     * instance token could not be created.
     */
    static final String JVM_SUFFIX = JVM_ID.isEmpty() ? "" : "-JVM" + JVM_ID;

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

        asString = "N" + nodeId + "-" + type.key + sequenceNumber + JVM_SUFFIX;
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

    /**
     * @return the connection-level correlation ID in the format of {@code N#-[STR|SVC]#-JVM<token>}. This is the value
     * sent to block nodes and used to identify this connection in logs.
     */
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

    /**
     * @return the JVM instance token, or an empty string if it could not be created
     */
    public static @NonNull String jvmId() {
        return JVM_ID;
    }

    /**
     * Builds a random JVM instance token. This is called from a static initializer, so it must never throw: if the
     * source of randomness cannot be created or used, a single warning is logged and an empty string is returned.
     *
     * @param randomSupplier supplies the source of randomness; it is called inside the failure handling so that errors
     *     creating the source (e.g. a misconfigured security provider) are also handled
     * @return the JVM instance token, or an empty string if it could not be created
     */
    static @NonNull String buildJvmId(@NonNull final Supplier<? extends RandomGenerator> randomSupplier) {
        try {
            return formatJvmId(randomSupplier.get().nextLong(JVM_ID_BOUND));
        } catch (final RuntimeException e) {
            logger.warn("Unable to create JVM instance ID; correlation IDs will not include it", e);
            return "";
        }
    }

    /**
     * Formats a value as a JVM instance token of exactly {@link #JVM_ID_LENGTH} zero-padded base-36 characters.
     *
     * @param value the value to format, in the range [0, {@link #JVM_ID_BOUND})
     * @return the JVM instance token
     */
    static @NonNull String formatJvmId(final long value) {
        if (value < 0 || value >= JVM_ID_BOUND) {
            throw new IllegalArgumentException("Value must be in the range [0, " + JVM_ID_BOUND + ")");
        }
        final String token = Long.toString(value, Character.MAX_RADIX);
        return "0".repeat(JVM_ID_LENGTH - token.length()) + token;
    }
}
