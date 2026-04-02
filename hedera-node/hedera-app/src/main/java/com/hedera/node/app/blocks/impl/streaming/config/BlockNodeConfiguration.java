// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import static java.util.Objects.requireNonNull;

import com.hedera.node.internal.network.BlockNodeConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * Configuration for a single block node.
 */
public class BlockNodeConfiguration {
    /**
     * Default message soft limit size - in bytes: 2 MB.
     */
    public static final long DEFAULT_MESSAGE_SOFT_LIMIT_BYTES = 2L * 1024 * 1024; // 2 MB
    /**
     * The streaming endpoint associated with this block node.
     */
    private final BlockNodeEndpoint streamingEndpoint;
    /**
     * The service endpoint associated with this block node.
     */
    private final BlockNodeEndpoint serviceEndpoint;
    /**
     * Priority of the block node.
     */
    private final int priority;
    /**
     * Message size soft limit (in bytes). This size represents the max size of a typical request.
     */
    private final long messageSizeSoftLimitBytes;
    /**
     * Message size hard limit (in bytes). This size represents the max size a single request may "burst" up to when
     * very large block items need to be sent that exceed the soft limit size.
     */
    private final long messageSizeHardLimitBytes;
    /**
     * Custom Helidon client HTTP/2 configuration.
     */
    private final BlockNodeHelidonHttpConfiguration clientHttpConfig;
    /**
     * Custom Helidon client gRPC configuration.
     */
    private final BlockNodeHelidonGrpcConfiguration clientGrpcConfig;

    private BlockNodeConfiguration(final Builder builder) {
        requireNonNull(builder.address, "Address must be specified");
        clientHttpConfig = requireNonNull(builder.clientHttpConfig, "Client HTTP config must be specified");
        clientGrpcConfig = requireNonNull(builder.clientGrpcConfig, "Client gRPC config must be specified");
        // default the service port to the streaming port
        final int servicePort = builder.servicePort == -1 ? builder.streamingPort : builder.servicePort;
        priority = builder.priority;
        messageSizeSoftLimitBytes = builder.messageSizeSoftLimitBytes;
        messageSizeHardLimitBytes = builder.messageSizeHardLimitBytes;

        if (builder.address.isBlank()) {
            throw new IllegalArgumentException("Address must not be empty");
        }
        if (builder.streamingPort < 1) {
            throw new IllegalArgumentException("Streaming port must be greater than or equal to 1");
        }
        if (servicePort < 1) {
            throw new IllegalArgumentException("Service port must be greater than or equal to 1");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("Priority must be greater than or equal to 0");
        }
        if (messageSizeSoftLimitBytes <= 0) {
            throw new IllegalArgumentException("Message size soft limit must be greater than 0");
        }
        if (messageSizeHardLimitBytes < messageSizeSoftLimitBytes) {
            throw new IllegalArgumentException("Message size hard limit (" + messageSizeHardLimitBytes
                    + ") must be greater than or equal to soft limit size (" + messageSizeSoftLimitBytes + ")");
        }

        streamingEndpoint = new BlockNodeEndpoint(builder.address, builder.streamingPort);
        serviceEndpoint = new BlockNodeEndpoint(builder.address, servicePort);
    }

    public @NonNull BlockNodeEndpoint streamingEndpoint() {
        return streamingEndpoint;
    }

    public @NonNull BlockNodeEndpoint serviceEndpoint() {
        return serviceEndpoint;
    }

    public @NonNull String address() {
        return streamingEndpoint.host();
    }

    public int streamingPort() {
        return streamingEndpoint.port();
    }

    public int servicePort() {
        return serviceEndpoint.port();
    }

    public int priority() {
        return priority;
    }

    public long messageSizeSoftLimitBytes() {
        return messageSizeSoftLimitBytes;
    }

    public long messageSizeHardLimitBytes() {
        return messageSizeHardLimitBytes;
    }

    public @NonNull BlockNodeHelidonHttpConfiguration clientHttpConfig() {
        return clientHttpConfig;
    }

    public @NonNull BlockNodeHelidonGrpcConfiguration clientGrpcConfig() {
        return clientGrpcConfig;
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BlockNodeConfiguration that = (BlockNodeConfiguration) o;
        return priority == that.priority
                && messageSizeSoftLimitBytes == that.messageSizeSoftLimitBytes
                && messageSizeHardLimitBytes == that.messageSizeHardLimitBytes
                && Objects.equals(streamingEndpoint, that.streamingEndpoint)
                && Objects.equals(serviceEndpoint, that.serviceEndpoint)
                && Objects.equals(clientHttpConfig, that.clientHttpConfig)
                && Objects.equals(clientGrpcConfig, that.clientGrpcConfig);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                streamingEndpoint,
                serviceEndpoint,
                priority,
                messageSizeSoftLimitBytes,
                messageSizeHardLimitBytes,
                clientHttpConfig,
                clientGrpcConfig);
    }

    @Override
    public String toString() {
        return "BlockNodeConfiguration{" + "streamingEndpoint="
                + streamingEndpoint + ", serviceEndpoint="
                + serviceEndpoint + ", priority="
                + priority + ", messageSizeSoftLimitBytes="
                + messageSizeSoftLimitBytes + ", messageSizeHardLimitBytes="
                + messageSizeHardLimitBytes + ", clientHttpConfig="
                + clientHttpConfig + ", clientGrpcConfig="
                + clientGrpcConfig + '}';
    }

    public static @NonNull BlockNodeConfiguration from(
            @NonNull final BlockNodeConfig config, final long defaultHardLimitBytes) {
        requireNonNull(config, "config must be specified");

        final Builder b = newBuilder();

        b.address(config.address());
        b.streamingPort(config.streamingPort());
        b.servicePort(config.servicePortOrElse(-1));
        b.priority(config.priority());
        b.messageSizeSoftLimitBytes(config.messageSizeSoftLimitBytesOrElse(DEFAULT_MESSAGE_SOFT_LIMIT_BYTES));
        b.messageSizeHardLimitBytes(config.messageSizeHardLimitBytesOrElse(defaultHardLimitBytes));
        b.clientGrpcConfig(BlockNodeHelidonGrpcConfiguration.from(config.clientGrpcConfig()));
        b.clientHttpConfig(BlockNodeHelidonHttpConfiguration.from(config.clientHttpConfig()));

        return b.build();
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private String address;
        private int streamingPort;
        private int servicePort = -1;
        private int priority;
        private long messageSizeSoftLimitBytes;
        private long messageSizeHardLimitBytes;
        private BlockNodeHelidonGrpcConfiguration clientGrpcConfig;
        private BlockNodeHelidonHttpConfiguration clientHttpConfig;

        private Builder() {
            // no-op
        }

        public @NonNull Builder address(final String address) {
            this.address = address;
            return this;
        }

        public @NonNull Builder streamingPort(final int streamingPort) {
            this.streamingPort = streamingPort;
            return this;
        }

        public @NonNull Builder servicePort(final int servicePort) {
            this.servicePort = servicePort;
            return this;
        }

        public @NonNull Builder priority(final int priority) {
            this.priority = priority;
            return this;
        }

        public @NonNull Builder messageSizeSoftLimitBytes(final long messageSizeSoftLimitBytes) {
            this.messageSizeSoftLimitBytes = messageSizeSoftLimitBytes;
            return this;
        }

        public @NonNull Builder messageSizeHardLimitBytes(final long messageSizeHardLimitBytes) {
            this.messageSizeHardLimitBytes = messageSizeHardLimitBytes;
            return this;
        }

        public @NonNull Builder clientHttpConfig(@NonNull final BlockNodeHelidonHttpConfiguration clientHttpConfig) {
            this.clientHttpConfig = clientHttpConfig;
            return this;
        }

        public @NonNull Builder clientGrpcConfig(@NonNull final BlockNodeHelidonGrpcConfiguration clientGrpcConfig) {
            this.clientGrpcConfig = clientGrpcConfig;
            return this;
        }

        public BlockNodeConfiguration build() {
            return new BlockNodeConfiguration(this);
        }
    }
}
