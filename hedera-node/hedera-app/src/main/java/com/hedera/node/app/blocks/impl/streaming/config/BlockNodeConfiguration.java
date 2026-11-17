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
    /**
     * TLS configuration for the streaming endpoint.
     */
    private final BlockNodeTlsConfiguration streamingTls;
    /**
     * TLS configuration for the service endpoint.
     */
    private final BlockNodeTlsConfiguration serviceTls;
    /**
     * Which API, if any, took its TLS settings from the other because both share one endpoint.
     */
    private final TlsInheritance tlsInheritance;
    /**
     * Whether that inheritance overrode a TLS block the operator explicitly declared with {@code enabled: false},
     * rather than filling in a block that was absent.
     */
    private final boolean tlsInheritanceOverrodeExplicitSetting;

    private BlockNodeConfiguration(final Builder builder) {
        requireNonNull(builder.address, "Address must be specified");
        clientHttpConfig = requireNonNull(builder.clientHttpConfig, "Client HTTP config must be specified");
        clientGrpcConfig = requireNonNull(builder.clientGrpcConfig, "Client gRPC config must be specified");
        // default the service port to the streaming port
        final int servicePort = builder.servicePort == -1 ? builder.streamingPort : builder.servicePort;
        streamingEndpoint = new BlockNodeEndpoint(builder.address, builder.streamingPort);
        serviceEndpoint = new BlockNodeEndpoint(builder.address, servicePort);
        final BlockNodeTlsConfiguration declaredStreamingTls =
                builder.streamingTls == null ? BlockNodeTlsConfiguration.DISABLED : builder.streamingTls;
        final BlockNodeTlsConfiguration declaredServiceTls =
                builder.serviceTls == null ? BlockNodeTlsConfiguration.DISABLED : builder.serviceTls;
        if (streamingEndpoint.equals(serviceEndpoint)) {
            final SharedEndpointTls shared =
                    resolveSharedEndpointTls(builder, declaredStreamingTls, declaredServiceTls, streamingEndpoint);
            streamingTls = shared.tls();
            serviceTls = shared.tls();
            tlsInheritance = shared.inheritance();
            tlsInheritanceOverrodeExplicitSetting = shared.overrodeExplicitSetting();
        } else {
            // Separate listeners are secured independently.
            streamingTls = declaredStreamingTls;
            serviceTls = declaredServiceTls;
            tlsInheritance = TlsInheritance.NONE;
            tlsInheritanceOverrodeExplicitSetting = false;
        }
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
    }

    /**
     * Reconciles the TLS settings of the two APIs when they share one endpoint.
     * <p>
     * A single listener negotiates TLS before it knows which API is being called, so it has exactly one TLS state and
     * the two APIs cannot differ. The rule is "most secure wins": if exactly one API requires TLS, that configuration
     * applies to both; if neither does, both are plaintext; if both require TLS they must agree exactly, because one
     * listener presents one certificate, so two different TLS configurations for it is a contradiction and the node is
     * rejected. Whether the non-TLS side was left unset or explicitly disabled does not change the result, but it is
     * reported so that an explicit setting being overridden can be logged more loudly than an absent one being filled.
     *
     * @param builder the builder being validated
     * @param streamingTls the TLS settings declared for the streaming API, or {@code DISABLED} if none
     * @param serviceTls the TLS settings declared for the service API, or {@code DISABLED} if none
     * @param endpoint the endpoint both APIs share
     * @return the single TLS configuration for the endpoint, and how it was arrived at
     */
    private static @NonNull SharedEndpointTls resolveSharedEndpointTls(
            @NonNull final Builder builder,
            @NonNull final BlockNodeTlsConfiguration streamingTls,
            @NonNull final BlockNodeTlsConfiguration serviceTls,
            @NonNull final BlockNodeEndpoint endpoint) {
        if (streamingTls.enabled() && serviceTls.enabled()) {
            if (!streamingTls.equals(serviceTls)) {
                throw new IllegalArgumentException("The streaming and service APIs share endpoint " + endpoint.host()
                        + ":" + endpoint.port()
                        + " and both require TLS, but with different settings: the streaming API"
                        + " declares " + streamingTls + " and the service API declares " + serviceTls
                        + "; a single listener presents one certificate, so make the two settings match or give the"
                        + " service API its own port");
            }
            return new SharedEndpointTls(streamingTls, TlsInheritance.NONE, false);
        }
        if (streamingTls.enabled()) {
            return new SharedEndpointTls(
                    streamingTls, TlsInheritance.SERVICE_FROM_STREAMING, builder.serviceTls != null);
        }
        if (serviceTls.enabled()) {
            return new SharedEndpointTls(
                    serviceTls, TlsInheritance.STREAMING_FROM_SERVICE, builder.streamingTls != null);
        }
        return new SharedEndpointTls(BlockNodeTlsConfiguration.DISABLED, TlsInheritance.NONE, false);
    }

    /**
     * The outcome of reconciling TLS settings on a shared endpoint.
     *
     * @param tls the TLS configuration both APIs use
     * @param inheritance which API, if any, took its settings from the other
     * @param overrodeExplicitSetting whether the API that took the other's settings had explicitly disabled TLS
     */
    private record SharedEndpointTls(
            @NonNull BlockNodeTlsConfiguration tls,
            @NonNull TlsInheritance inheritance,
            boolean overrodeExplicitSetting) {}

    /**
     * Which API, if any, took its TLS settings from the other because the two share one endpoint.
     */
    public enum TlsInheritance {
        /** The endpoints differ, or both APIs already agreed; nothing was changed. */
        NONE,
        /** The service API uses the streaming API's TLS settings. */
        SERVICE_FROM_STREAMING,
        /** The streaming API uses the service API's TLS settings. */
        STREAMING_FROM_SERVICE
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

    public @NonNull BlockNodeTlsConfiguration streamingTls() {
        return streamingTls;
    }

    /**
     * The TLS settings applied to the service endpoint. When the two APIs share one endpoint this is the single
     * configuration resolved for that endpoint, which may have come from the streaming API; see
     * {@link #tlsInheritance()}.
     *
     * @return the TLS settings for the service endpoint
     */
    public @NonNull BlockNodeTlsConfiguration serviceTls() {
        return serviceTls;
    }

    /**
     * @return which API, if any, took its TLS settings from the other because both share one endpoint
     */
    public @NonNull TlsInheritance tlsInheritance() {
        return tlsInheritance;
    }

    /**
     * @return true if {@link #tlsInheritance()} overrode a TLS block the operator explicitly declared with
     * {@code enabled: false}, as opposed to filling in a block that was absent
     */
    public boolean tlsInheritanceOverrodeExplicitSetting() {
        return tlsInheritanceOverrodeExplicitSetting;
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
                && Objects.equals(clientGrpcConfig, that.clientGrpcConfig)
                && Objects.equals(streamingTls, that.streamingTls)
                && Objects.equals(serviceTls, that.serviceTls);
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
                clientGrpcConfig,
                streamingTls,
                serviceTls);
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
                + clientGrpcConfig + ", streamingTls="
                + streamingTls + ", serviceTls="
                + serviceTls + '}';
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
        // Leave a TLS block unset when the file omits it; the constructor decides what an omitted block means.
        if (config.streamingTls() != null) {
            b.streamingTls(BlockNodeTlsConfiguration.from(config.streamingTls()));
        }
        if (config.serviceTls() != null) {
            b.serviceTls(BlockNodeTlsConfiguration.from(config.serviceTls()));
        }

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
        private BlockNodeTlsConfiguration streamingTls;
        private BlockNodeTlsConfiguration serviceTls;

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

        public @NonNull Builder streamingTls(@NonNull final BlockNodeTlsConfiguration streamingTls) {
            this.streamingTls = streamingTls;
            return this;
        }

        public @NonNull Builder serviceTls(@NonNull final BlockNodeTlsConfiguration serviceTls) {
            this.serviceTls = serviceTls;
            return this;
        }

        public BlockNodeConfiguration build() {
            return new BlockNodeConfiguration(this);
        }
    }
}
