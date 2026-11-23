// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public abstract class BlockStreamingBase {

    /**
     * Default timeout for connection management operations (e.g. connection create).
     */
    private static final long DEFAULT_CONNECTION_MANAGEMENT_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(3);
    /**
     * Default timeout for calling the server status API.
     */
    private static final long DEFAULT_STATUS_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(2);
    /**
     * Mechanism to retrieve configuration data.
     */
    private final ConfigProvider configProvider;

    BlockStreamingBase(@NonNull final ConfigProvider configProvider) {
        this.configProvider = requireNonNull(configProvider, "configProvider is required");
    }

    /**
     * @return the configuration provider used by this connection
     */
    final @NonNull ConfigProvider configProvider() {
        return configProvider;
    }

    /**
     * @return true if block node streaming is enabled, else false
     */
    final boolean isStreamingEnabled() {
        return configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamToBlockNodes();
    }

    /**
     * @return the current {@link BlockNodeConnectionConfig} instance
     */
    final @NonNull BlockNodeConnectionConfig bncConfig() {
        return configProvider.getConfiguration().getConfigData(BlockNodeConnectionConfig.class);
    }

    /**
     * @return the current {@link BlockStreamConfig} instance
     */
    final @NonNull BlockStreamConfig bsConfig() {
        return configProvider.getConfiguration().getConfigData(BlockStreamConfig.class);
    }

    /**
     * @return the current {@link BlockBufferConfig} instance
     */
    final @NonNull BlockBufferConfig bufferConfig() {
        return configProvider.getConfiguration().getConfigData(BlockBufferConfig.class);
    }

    /**
     * Back pressure applies only when block nodes are the sole sink of a {@code BLOCKS} stream; with a file writer as
     * well, every block stays on disk, so a saturated buffer prunes unacknowledged blocks instead of stalling.
     *
     * @return true if back pressure is enabled, else false
     */
    final boolean isBackpressureEnabled() {
        final var config = bsConfig();
        return config.streamMode() == StreamMode.BLOCKS && config.writerMode() == BlockStreamWriterMode.GRPC;
    }

    /**
     * @return the connection management timeout in milliseconds
     */
    final long connectionManagementTimeoutMillis() {
        final Duration configValue = bncConfig().connectionManagementTimeout();
        if (configValue == null || !configValue.isPositive()) {
            return DEFAULT_CONNECTION_MANAGEMENT_TIMEOUT_MILLIS;
        } else {
            return configValue.toMillis();
        }
    }

    /**
     * @return the status API timeout in milliseconds
     */
    final long statusTimeoutMillis() {
        final Duration configValue = bncConfig().blockNodeStatusTimeout();
        if (configValue == null || !configValue.isPositive()) {
            return DEFAULT_STATUS_TIMEOUT_MILLIS;
        } else {
            return configValue.toMillis();
        }
    }
}
