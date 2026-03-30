package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public record ConnectionId(@NonNull ConnectionType type, int id) {
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

    public static ConnectionId newConnectionId(@NonNull final ConnectionType type) {
        final int id = connIdCtrByType.get(type).incrementAndGet();
        return new ConnectionId(type, id);
    }

    @Override
    public @NonNull String toString() {
        return String.format("%s.%06d", type.key, id);
    }
}
