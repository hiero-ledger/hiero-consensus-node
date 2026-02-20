// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.config;

import com.hedera.node.internal.network.HelidonGrpcConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import io.helidon.webclient.grpc.GrpcClientProtocolConfig;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Proxy configuration for Helidon gRPC client.
 */
public class BlockNodeHelidonGrpcConfiguration {
    /**
     * Default "empty" configuration.
     */
    public static final BlockNodeHelidonGrpcConfiguration DEFAULT = newBuilder().build();

    private final Boolean abortPollTimeExpired;
    private final Duration heartbeatPeriod;
    private final Integer initialBufferSize;
    private final String name;
    private final Duration pollWaitTime;

    private BlockNodeHelidonGrpcConfiguration(final Builder builder) {
        abortPollTimeExpired = builder.abortPollTimeExpired;
        heartbeatPeriod = builder.heartbeatPeriod;
        initialBufferSize = builder.initialBufferSize;
        name = builder.name;
        pollWaitTime = builder.pollWaitTime;
    }

    public @NonNull Optional<Boolean> abortPollTimeExpired() {
        return Optional.ofNullable(abortPollTimeExpired);
    }

    public @NonNull Optional<Duration> heartbeatPeriod() {
        return Optional.ofNullable(heartbeatPeriod);
    }

    public @NonNull Optional<Integer> initialBufferSize() {
        return Optional.ofNullable(initialBufferSize);
    }

    public @NonNull Optional<String> name() {
        return Optional.ofNullable(name);
    }

    public @NonNull Optional<Duration> pollWaitTime() {
        return Optional.ofNullable(pollWaitTime);
    }

    /**
     * Converts this gRPC configuration to a Helidon-specific protocol configuration.
     *
     * @return the converted Helidon gRPC protocol configuration
     */
    public @NonNull GrpcClientProtocolConfig toGrpcClientProtocolConfig() {
        final GrpcClientProtocolConfig.Builder b = GrpcClientProtocolConfig.builder();

        abortPollTimeExpired().ifPresent(b::abortPollTimeExpired);
        heartbeatPeriod().ifPresent(b::heartbeatPeriod);
        initialBufferSize().ifPresent(b::initBufferSize);
        name().ifPresent(b::name);
        pollWaitTime().ifPresent(b::pollWaitTime);

        return b.build();
    }

    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final BlockNodeHelidonGrpcConfiguration that = (BlockNodeHelidonGrpcConfiguration) o;
        return Objects.equals(abortPollTimeExpired, that.abortPollTimeExpired)
                && Objects.equals(heartbeatPeriod, that.heartbeatPeriod)
                && Objects.equals(initialBufferSize, that.initialBufferSize)
                && Objects.equals(name, that.name)
                && Objects.equals(pollWaitTime, that.pollWaitTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(abortPollTimeExpired, heartbeatPeriod, initialBufferSize, name, pollWaitTime);
    }

    @Override
    public String toString() {
        return "BlockNodeHelidonGrpcConfiguration{" + "abortPollTimeExpired="
                + abortPollTimeExpired + ", heartbeatPeriod="
                + heartbeatPeriod + ", initialBufferSize="
                + initialBufferSize + ", name="
                + (name == null ? null : "'" + name + "'") + ", pollWaitTime="
                + pollWaitTime + '}';
    }

    public static @NonNull Builder newBuilder() {
        return new Builder();
    }

    /**
     * Converts a HelidonGrpcConfig proto to a BlockNodeHelidonGrpcConfiguration object.
     *
     * @param grpcConfig the original configuration to extract
     * @return the extracted configuration
     */
    public static @NonNull BlockNodeHelidonGrpcConfiguration from(@Nullable final HelidonGrpcConfig grpcConfig) {
        if (grpcConfig == null) {
            return DEFAULT;
        }

        final Builder b = newBuilder();

        b.abortPollTimeExpired(grpcConfig.abortPollTimeExpired());

        final String _heartbeatPeriod = grpcConfig.heartbeatPeriod();
        if (_heartbeatPeriod != null && !_heartbeatPeriod.isBlank()) {
            b.heartbeatPeriod(Duration.parse(_heartbeatPeriod));
        }

        b.initialBufferSize(grpcConfig.initBufferSize());
        b.name(grpcConfig.name());

        final String _pollWaitTime = grpcConfig.pollWaitTime();
        if (_pollWaitTime != null && !_pollWaitTime.isBlank()) {
            b.pollWaitTime(Duration.parse(_pollWaitTime));
        }

        return b.build();
    }

    public static class Builder {
        private Boolean abortPollTimeExpired;
        private Duration heartbeatPeriod;
        private Integer initialBufferSize;
        private String name;
        private Duration pollWaitTime;

        private Builder() {
            // no-op
        }

        public @NonNull Builder abortPollTimeExpired(@Nullable final Boolean abortPollTimeExpired) {
            this.abortPollTimeExpired = abortPollTimeExpired;
            return this;
        }

        public @NonNull Builder heartbeatPeriod(@Nullable final Duration heartbeatPeriod) {
            this.heartbeatPeriod = heartbeatPeriod;
            return this;
        }

        public @NonNull Builder initialBufferSize(@Nullable final Integer initialBufferSize) {
            this.initialBufferSize = initialBufferSize;
            return this;
        }

        public @NonNull Builder name(@Nullable final String name) {
            this.name = name;
            return this;
        }

        public @NonNull Builder pollWaitTime(@Nullable final Duration pollWaitTime) {
            this.pollWaitTime = pollWaitTime;
            return this;
        }

        public BlockNodeHelidonGrpcConfiguration build() {
            return new BlockNodeHelidonGrpcConfiguration(this);
        }
    }
}
