// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import com.hedera.node.internal.network.HelidonHttpConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.webclient.http2.Http2ClientProtocolConfig;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Proxy configuration for Helidon HTTP/2 client.
 */
public class BlockNodeHelidonHttpConfiguration {
    /**
     * Default "empty" configuration.
     */
    public static final BlockNodeHelidonHttpConfiguration DEFAULT = newBuilder().build();

    private final Duration flowControlBlockTimeout;
    private final Integer initialWindowSize;
    private final Integer maxFrameSize;
    private final Long maxHeaderListSize;
    private final String name;
    private final Boolean ping;
    private final Duration pingTimeout;
    private final Boolean priorKnowledge;

    private BlockNodeHelidonHttpConfiguration(final Builder builder) {
        flowControlBlockTimeout = builder.flowControlBlockTimeout;
        initialWindowSize = builder.initialWindowSize;
        maxFrameSize = builder.maxFrameSize;
        maxHeaderListSize = builder.maxHeaderListSize;
        name = builder.name;
        ping = builder.ping;
        pingTimeout = builder.pingTimeout;
        priorKnowledge = builder.priorKnowledge;
    }

    public @NonNull Optional<Duration> flowControlBlockTimeout() {
        return Optional.ofNullable(flowControlBlockTimeout);
    }

    public @NonNull Optional<Integer> initialWindowSize() {
        return Optional.ofNullable(initialWindowSize);
    }

    public @NonNull Optional<Integer> maxFrameSize() {
        return Optional.ofNullable(maxFrameSize);
    }

    public @NonNull Optional<Long> maxHeaderListSize() {
        return Optional.ofNullable(maxHeaderListSize);
    }

    public @NonNull Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public @NonNull Optional<Boolean> ping() {
        return Optional.ofNullable(ping);
    }

    public @NonNull Optional<Duration> pingTimeout() {
        return Optional.ofNullable(pingTimeout);
    }

    public @NonNull Optional<Boolean> priorKnowledge() {
        return Optional.ofNullable(priorKnowledge);
    }

    /**
     * Convert this HTTP/2 configuration to a Helidon-specific protocol configuration.
     *
     * @return the converted Helidon HTTP/2 protocol configuration
     */
    public @NonNull Http2ClientProtocolConfig toHttp2ClientProtocolConfig() {
        final Http2ClientProtocolConfig.Builder b = Http2ClientProtocolConfig.builder();

        flowControlBlockTimeout().ifPresent(b::flowControlBlockTimeout);
        initialWindowSize().ifPresent(b::initialWindowSize);
        maxFrameSize().ifPresent(b::maxFrameSize);
        maxHeaderListSize().ifPresent(b::maxHeaderListSize);
        name().ifPresent(b::name);
        ping().ifPresent(b::ping);
        pingTimeout().ifPresent(b::pingTimeout);
        priorKnowledge().ifPresent(b::priorKnowledge);

        return b.build();
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BlockNodeHelidonHttpConfiguration that = (BlockNodeHelidonHttpConfiguration) o;
        return Objects.equals(flowControlBlockTimeout, that.flowControlBlockTimeout)
                && Objects.equals(initialWindowSize, that.initialWindowSize)
                && Objects.equals(maxFrameSize, that.maxFrameSize)
                && Objects.equals(maxHeaderListSize, that.maxHeaderListSize)
                && Objects.equals(name, that.name)
                && Objects.equals(ping, that.ping)
                && Objects.equals(pingTimeout, that.pingTimeout)
                && Objects.equals(priorKnowledge, that.priorKnowledge);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                flowControlBlockTimeout,
                initialWindowSize,
                maxFrameSize,
                maxHeaderListSize,
                name,
                ping,
                pingTimeout,
                priorKnowledge);
    }

    @Override
    public String toString() {
        return "BlockNodeHelidonHttpConfiguration{" + "flowControlBlockTimeout="
                + flowControlBlockTimeout + ", initialWindowSize="
                + initialWindowSize + ", maxFrameSize="
                + maxFrameSize + ", maxHeaderListSize="
                + maxHeaderListSize + ", name="
                + (name == null ? null : "'" + name + "'") + ", ping="
                + ping + ", pingTimeout="
                + pingTimeout + ", priorKnowledge="
                + priorKnowledge + '}';
    }

    /**
     * Converts a HelidonHttpConfig proto to a BlockNodeHelidonHttpConfiguration object.
     *
     * @param httpConfig the original configuration to extract
     * @return the extracted configuration
     */
    public static @NonNull BlockNodeHelidonHttpConfiguration from(@Nullable final HelidonHttpConfig httpConfig) {
        if (httpConfig == null) {
            return DEFAULT;
        }

        final Builder b = newBuilder();

        final String _flowControlBlockTimeout = httpConfig.flowControlBlockTimeout();
        if (_flowControlBlockTimeout != null && !_flowControlBlockTimeout.isBlank()) {
            b.flowControlBlockTimeout(Duration.parse(_flowControlBlockTimeout));
        }

        b.initialWindowSize(httpConfig.initialWindowSize());
        b.maxFrameSize(httpConfig.maxFrameSize());
        b.maxHeaderListSize(httpConfig.maxHeaderListSize());
        b.name(httpConfig.name());
        b.ping(httpConfig.ping());

        final String _pingTimeout = httpConfig.pingTimeout();
        if (_pingTimeout != null && !_pingTimeout.isBlank()) {
            b.pingTimeout(Duration.parse(_pingTimeout));
        }

        b.priorKnowledge(httpConfig.priorKnowledge());

        return b.build();
    }

    public static @NonNull Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {
        private Duration flowControlBlockTimeout;
        private Integer initialWindowSize;
        private Integer maxFrameSize;
        private Long maxHeaderListSize;
        private String name;
        private Boolean ping;
        private Duration pingTimeout;
        private Boolean priorKnowledge;

        private Builder() {
            // no-op
        }

        public @NonNull Builder flowControlBlockTimeout(@Nullable final Duration flowControlBlockTimeout) {
            this.flowControlBlockTimeout = flowControlBlockTimeout;
            return this;
        }

        public @NonNull Builder initialWindowSize(@Nullable final Integer initialWindowSize) {
            this.initialWindowSize = initialWindowSize;
            return this;
        }

        public @NonNull Builder maxFrameSize(@Nullable final Integer maxFrameSize) {
            this.maxFrameSize = maxFrameSize;
            return this;
        }

        public @NonNull Builder maxHeaderListSize(@Nullable final Long maxHeaderListSize) {
            this.maxHeaderListSize = maxHeaderListSize;
            return this;
        }

        public @NonNull Builder name(@Nullable final String name) {
            this.name = name;
            return this;
        }

        public @NonNull Builder ping(@Nullable final Boolean ping) {
            this.ping = ping;
            return this;
        }

        public @NonNull Builder pingTimeout(@Nullable final Duration pingTimeout) {
            this.pingTimeout = pingTimeout;
            return this;
        }

        public @NonNull Builder priorKnowledge(@Nullable final Boolean priorKnowledge) {
            this.priorKnowledge = priorKnowledge;
            return this;
        }

        public BlockNodeHelidonHttpConfiguration build() {
            return new BlockNodeHelidonHttpConfiguration(this);
        }
    }
}
