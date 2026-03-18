// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.pbj.runtime.grpc.GrpcException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base implementation for a connection to a block node.
 */
public abstract class AbstractBlockNodeConnection implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(AbstractBlockNodeConnection.class);

    enum ConnectionType {
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

    /**
     * The block node configuration.
     */
    private final BlockNodeConfiguration configuration;
    /**
     * The unique (for the life of the JVM) connection identifier.
     */
    private final String connectionId;
    /**
     * Mechanism to retrieve configuration data.
     */
    private final ConfigProvider configProvider;
    /**
     * The current state of this connection.
     */
    private final AtomicReference<ConnectionState> stateRef;
    /**
     * The type of connection this instance represents.
     */
    private final ConnectionType type;

    /**
     * Initialize this connection.
     *
     * @param type the type of connection being created
     * @param configuration the block node configuration associated with this connection
     * @param configProvider the {@link ConfigProvider} that can be used to retrieve configuration data
     */
    AbstractBlockNodeConnection(
            @NonNull final ConnectionType type,
            @NonNull final BlockNodeConfiguration configuration,
            @NonNull final ConfigProvider configProvider) {
        this.configuration = requireNonNull(configuration, "configuration is required");
        this.configProvider = requireNonNull(configProvider, "configProvider is required");
        this.type = requireNonNull(type, "type is required");

        connectionId =
                String.format("%s.%06d", type.key, connIdCtrByType.get(type).incrementAndGet());
        stateRef = new AtomicReference<>(ConnectionState.UNINITIALIZED);
    }

    /**
     * @return the unique identifier for this connection
     */
    final @NonNull String connectionId() {
        return connectionId;
    }

    /**
     * Update this connection's state to the specified state.
     *
     * @param newState the new state to update to
     */
    final void updateConnectionState(@NonNull final ConnectionState newState) {
        updateConnectionState(null, newState);
    }

    /**
     * Update this connection's state to the specified new state if the current state matches the expected state.
     *
     * @param expectedState the expected current state of this connection
     * @param newState the new state to update to
     * @return true if the update was successful, else false (e.g. current state did not match expected state)
     */
    final boolean updateConnectionState(
            @Nullable final ConnectionState expectedState, @NonNull final ConnectionState newState) {
        requireNonNull(newState, "newState is required");

        final ConnectionState latestState = stateRef.get();

        if (!latestState.canTransitionTo(newState)) {
            logger.warn(
                    "{} Attempted to downgrade state from {} to {}, but this is not allowed",
                    this,
                    latestState,
                    newState);
            throw new IllegalArgumentException("Attempted to downgrade state from " + latestState + " to " + newState);
        }

        if (expectedState != null && expectedState != latestState) {
            logger.debug("{} Current state ({}) does not match expected state ({})", this, latestState, expectedState);
            return false;
        }

        if (!stateRef.compareAndSet(latestState, newState)) {
            logger.debug(
                    "{} Failed to transition state from {} to {} because current state does not match expected state",
                    this,
                    latestState,
                    newState);
            return false;
        }

        logger.info("{} Connection state transitioned from {} to {}", this, latestState, newState);

        final ConnectionState state = stateRef.get();
        if (state == ConnectionState.ACTIVE) {
            onActiveStateTransition();
        } else if (state.isTerminal()) {
            onTerminalStateTransition();
        }

        return true;
    }

    /**
     * Convenience method for checking if the current connection is active.
     *
     * @return true if this connection's state is ACTIVE, else false
     */
    final boolean isActive() {
        return currentState() == ConnectionState.ACTIVE;
    }

    /**
     * Handler that is called when this connection's state transitions to active. (default: no-op)
     */
    void onActiveStateTransition() {
        // no-op
    }

    /**
     * Handler that is called when this connection's state transitions to a terminal state. Note: there are multiple
     * terminal states and thus this method may be called more than once. (default: no-op)
     */
    void onTerminalStateTransition() {
        // no-op
    }

    /**
     * Initialize this connection by creating the underlying client/socket to a block node. If successful, this
     * connection should have its state updated to READY.
     */
    abstract void initialize();

    /**
     * Closes this connection. The connection should transition to a CLOSING state upon entering this method and then
     * at the conclusion of this method (either successful or failed) the state should transition to CLOSED.
     */
    public abstract void close();

    /**
     * @return the configuration provider used by this connection
     */
    final @NonNull ConfigProvider configProvider() {
        return configProvider;
    }

    /**
     * @return the current state of this connection
     */
    public final @NonNull ConnectionState currentState() {
        return stateRef.get();
    }

    /**
     * @return the block node configuration associated with this connection
     */
    public final @NonNull BlockNodeConfiguration configuration() {
        return configuration;
    }

    /**
     * Given a throwable, determine if the throwable or one of its causes is a {@link GrpcException}.
     *
     * @param t the throwable to search
     * @return the {@link GrpcException} associated with this throwable, or null if one is not found
     */
    protected GrpcException findGrpcException(final Throwable t) {
        Throwable th = t;

        while (th != null) {
            if (th instanceof final GrpcException grpcException) {
                return grpcException;
            }

            th = th.getCause();
        }

        return null;
    }

    @Override
    public final String toString() {
        final int port =
                switch (type) {
                    case BLOCK_STREAMING -> configuration.streamingPort();
                    case SERVER_STATUS -> configuration.servicePort();
                };

        return "[" + connectionId + "/" + configuration.address() + ":" + port + "/" + stateRef.get() + "]";
    }

    @Override
    public final boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final AbstractBlockNodeConnection that = (AbstractBlockNodeConnection) o;
        return Objects.equals(configuration, that.configuration) && Objects.equals(connectionId, that.connectionId);
    }

    @Override
    public final int hashCode() {
        return Objects.hash(configuration, connectionId);
    }
}
