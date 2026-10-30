// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.StreamMode;
import edu.umd.cs.findbugs.annotations.NonNull;

public abstract class BlockStreamingBase {
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
     * @return true if back pressure is enabled, else false
     */
    final boolean isBackpressureEnabled() {
        return bsConfig().streamMode() == StreamMode.BLOCKS && isStreamingEnabled();
    }
}
