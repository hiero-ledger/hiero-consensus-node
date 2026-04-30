// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.app.blocks.impl.streaming.ConnectionId.ConnectionType;
import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.pbj.runtime.grpc.GrpcException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Base implementation for a connection to a block node.
 */
public abstract class AbstractBlockNodeConnection implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(AbstractBlockNodeConnection.class);

    /**
     * The block node configuration.
     */
    private final BlockNodeConfiguration configuration;
    /**
     * The unique (for the life of the JVM) connection identifier.
     */
    private final ConnectionId connectionId;
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
     * The timestamp of when this connection was created.
     */
    private final Instant createTimestamp;
    /**
     * The timestamp of when this connection became active - else null if it hasn't transitioned to active.
     */
    private final AtomicReference<Instant> activeTimestampRef = new AtomicReference<>();
    /**
     * The timestamp of when this connection became closed - else null if it hasn't transitioned to closed.
     */
    private final AtomicReference<Instant> closeTimestampRef = new AtomicReference<>();
    /**
     * The reason why the connection was closed - else null if the connection wasn't closed or no reason was provided.
     */
    private final AtomicReference<CloseReason> closeReasonRef = new AtomicReference<>();
    /**
     * An integer representation of the IP address.
     */
    private volatile long ipAsInteger = -1;
    /**
     * Flag indicating whether resolving the IP address failed and whether it was the first time encountering the issue.
     * This is mainly used as a control mechanism to ensure we only log the resolution failure once, instead of every
     * time the method to retrieve the IP fails.
     */
    private volatile boolean isInitialIpError = true;

    /**
     * Initialize this connection.
     *
     * @param type the type of connection being created
     * @param configuration the block node configuration associated with this connection
     * @param configProvider the {@link ConfigProvider} that can be used to retrieve configuration data
     * @param nodeId the consensus node ID to include in correlation IDs
     */
    AbstractBlockNodeConnection(
            @NonNull final ConnectionType type,
            @NonNull final BlockNodeConfiguration configuration,
            @NonNull final ConfigProvider configProvider,
            final long nodeId) {
        this.configuration = requireNonNull(configuration, "configuration is required");
        this.configProvider = requireNonNull(configProvider, "configProvider is required");
        this.type = requireNonNull(type, "type is required");

        connectionId = ConnectionId.newConnectionId(nodeId, type);
        stateRef = new AtomicReference<>(ConnectionState.UNINITIALIZED);
        createTimestamp = Instant.now();
    }

    /**
     * @return the current {@link BlockNodeConnectionConfig} instance
     */
    final @NonNull BlockNodeConnectionConfig bncConfig() {
        return configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
    }

    /**
     * @return the IPv4 address represented as an integer, or -1 if the address could not be resolved or is not an IPv4 address
     */
    final long ipV4AddressAsInt() {
        if (ipAsInteger != -1) {
            return ipAsInteger;
        }

        try {
            final URL url = URI.create("http://" + configuration.address() + ":" + configuration.streamingPort())
                    .toURL();
            final InetAddress address = InetAddress.getByName(url.getHost());
            final byte[] bytes = address.getAddress();

            if (bytes.length != 4) {
                if (isInitialIpError) {
                    isInitialIpError = false;
                    logger.warn("Only IPv4 addresses are supported for conversion to integer");
                }
                return ipAsInteger;
            }

            final long octet1 = 256L * 256 * 256 * (bytes[0] & 0xFF);
            final long octet2 = 256L * 256 * (bytes[1] & 0xFF);
            final long octet3 = 256L * (bytes[2] & 0xFF);
            final long octet4 = 1L * (bytes[3] & 0xFF);
            ipAsInteger = octet1 + octet2 + octet3 + octet4;

            logger.info(
                    "{} Block node address ({}:{}) resolved to IP {} (as-integer: {})",
                    this,
                    configuration.address(),
                    configuration.streamingPort(),
                    address.getHostAddress(),
                    ipAsInteger);
        } catch (final IOException e) {
            if (isInitialIpError) {
                isInitialIpError = false;
                logger.warn(
                        "{} Failed to resolve block node host ({}:{})",
                        this,
                        configuration.address(),
                        configuration.streamingPort(),
                        e);
            }
        }

        return ipAsInteger;
    }

    /**
     * @return the unique identifier for this connection
     */
    final @NonNull ConnectionId connectionId() {
        return connectionId;
    }

    /**
     * Returns a request-level correlation ID for block-specific requests.
     *
     * @param blockNumber block number
     * @param blockRequestNumber request number scoped to the block
     * @return correlation ID in format N#-[STR|SVC]#-BLK#-REQ#
     */
    final @NonNull String blockRequestCorrelationId(final long blockNumber, final int blockRequestNumber) {
        return connectionId + "-BLK" + blockNumber + "-REQ" + blockRequestNumber;
    }

    /**
     * Builds a correlation ID for the specified connection request.
     *
     * @param connectionRequestNumber the connection-level request number
     * @return correlation ID in the format of N#-[STR|SVC]#-CRN#
     */
    final @NonNull String buildRequestCorrelationId(final long connectionRequestNumber) {
        return connectionId + "-CRN" + connectionRequestNumber;
    }

    /**
     * Builds a correlation ID for the specified block-level request.
     *
     * @param connectionRequestNumber the connection-level request number
     * @param blockNumber the block number
     * @param blockRequestNumber the block-level request number
     * @return correlation ID in the format of N#-[STR|SVC]#-BLK#-REQ#-CRN#
     */
    final @NonNull String buildRequestCorrelationId(
            final long connectionRequestNumber, final long blockNumber, final int blockRequestNumber) {
        return connectionId + "-BLK" + blockNumber + "-REQ" + blockRequestNumber + "-CRN" + connectionRequestNumber;
    }

    /**
     * Formats a connection context string using either a supplied correlation ID or this connection's base ID.
     *
     * @param correlationId correlation ID to display in the context, or null to use base connection ID
     * @return formatted context string in the form {@code [ID/host:port/STATE]}
     */
    final @NonNull String connectionContext(@Nullable final String correlationId) {
        final int port =
                switch (type) {
                    case BLOCK_STREAMING -> configuration.streamingPort();
                    case SERVER_STATUS -> configuration.servicePort();
                };
        final String idToDisplay =
                (correlationId == null || correlationId.isBlank()) ? connectionId.toString() : correlationId;
        return "[" + idToDisplay + "/" + configuration.address() + ":" + port + "/" + stateRef.get() + "]";
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

        logger.debug("{} Connection state transitioned from {} to {}", this, latestState, newState);

        final ConnectionState state = stateRef.get();
        if (state == ConnectionState.ACTIVE) {
            activeTimestampRef.set(Instant.now());
            onActiveStateTransition();
        } else if (state.isTerminal()) {
            if (state == ConnectionState.CLOSED) {
                closeTimestampRef.set(Instant.now());
            }

            onTerminalStateTransition();
        }

        return true;
    }

    /**
     * @return the timestamp of when this connection was created
     */
    final @NonNull Instant createTimestamp() {
        return createTimestamp;
    }

    /**
     * @return the timestamp of when this connection transitioned to the active state, or null if it hasn't transitioned
     */
    final @Nullable Instant activeTimestamp() {
        return activeTimestampRef.get();
    }

    /**
     * @return the timestamp of when this connection transitioned to the closed state, or null if it hasn't transitioned
     */
    final @Nullable Instant closeTimestamp() {
        return closeTimestampRef.get();
    }

    /**
     * Sets the close reason for this connection.
     *
     * @param closeReason the close reason
     */
    final void setCloseReason(@NonNull final CloseReason closeReason) {
        requireNonNull(closeReason, "Close reason is required");
        closeReasonRef.set(closeReason);
    }

    /**
     * @return the reason why this connection was closed, else null if a reason wasn't specified or the connection is
     * not closed
     */
    final @Nullable CloseReason closeReason() {
        return closeReasonRef.get();
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
        return connectionContext(null);
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
