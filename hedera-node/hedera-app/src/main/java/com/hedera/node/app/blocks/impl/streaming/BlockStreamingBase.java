// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static java.util.Objects.requireNonNull;

import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.BlockBufferConfig;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.StreamMode;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.locks.StampedLock;

public abstract class BlockStreamingBase {
    /**
     * Mechanism to retrieve configuration data.
     */
    private final ConfigProvider configProvider;

    /**
     * The configuration to use instead of the provider's while a transaction is trying out a configuration update, or
     * null when the provider's configuration is to be used. Written under the write lock of
     * {@link #heldConfigurationLock}.
     */
    private volatile Configuration heldConfiguration;

    /**
     * Lets a reader tell that a hold or a release ran while it was choosing between the held and the provider's
     * configuration, in which case what it read may belong to neither the state before nor the state after.
     */
    private final StampedLock heldConfigurationLock = new StampedLock();

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
     * Keeps this component on the given configuration until {@link #releaseConfiguration()}. Called with the committed
     * configuration before a transaction applies an update of a configuration file: the provider then returns the
     * updated configuration to every thread although the transaction may still roll it back, and settings read from it
     * in the meantime (limits, the acknowledgement requirement, back pressure, whether to stream at all) could prune
     * or acknowledge blocks in a way the committed configuration does not allow. If a configuration is already held it
     * is kept, so several updates in one transaction hold the configuration from before the first.
     *
     * <p>The caller must apply the update only after this method returns.
     *
     * @param configuration the committed configuration to hold
     */
    public void holdConfiguration(@NonNull final Configuration configuration) {
        requireNonNull(configuration, "configuration is required");
        final long stamp = heldConfigurationLock.writeLock();
        try {
            if (heldConfiguration == null) {
                heldConfiguration = configuration;
            }
        } finally {
            heldConfigurationLock.unlockWrite(stamp);
        }
    }

    /**
     * Returns this component to the provider's configuration. The caller must call this only once the outcome of the
     * transaction is final and the provider's configuration is a committed one again.
     */
    public void releaseConfiguration() {
        final long stamp = heldConfigurationLock.writeLock();
        try {
            heldConfiguration = null;
        } finally {
            heldConfigurationLock.unlockWrite(stamp);
        }
    }

    /**
     * Returns the held configuration if there is one, else the provider's current configuration, which is then a
     * committed one: a hold precedes every update that is not final, so a reader that finds no hold and reads the
     * provider's configuration without a hold or a release running in between cannot have read such an update.
     *
     * @return the configuration this component is to act on
     */
    private @NonNull Configuration configuration() {
        final long stamp = heldConfigurationLock.tryOptimisticRead();
        Configuration configuration = heldOrCurrentConfiguration();
        if (!heldConfigurationLock.validate(stamp)) {
            // A hold or a release ran in between, so read again while neither can run
            final long readStamp = heldConfigurationLock.readLock();
            try {
                configuration = heldOrCurrentConfiguration();
            } finally {
                heldConfigurationLock.unlockRead(readStamp);
            }
        }
        return configuration;
    }

    private @NonNull Configuration heldOrCurrentConfiguration() {
        final Configuration held = heldConfiguration;
        return held != null ? held : configProvider.getConfiguration();
    }

    /**
     * @return true if block node streaming is enabled, else false
     */
    final boolean isStreamingEnabled() {
        return configuration().getConfigData(BlockStreamConfig.class).streamToBlockNodes();
    }

    /**
     * @return the current {@link BlockNodeConnectionConfig} instance
     */
    final @NonNull BlockNodeConnectionConfig bncConfig() {
        return configuration().getConfigData(BlockNodeConnectionConfig.class);
    }

    /**
     * @return the current {@link BlockStreamConfig} instance
     */
    final @NonNull BlockStreamConfig bsConfig() {
        return configuration().getConfigData(BlockStreamConfig.class);
    }

    /**
     * @return the current {@link BlockBufferConfig} instance
     */
    final @NonNull BlockBufferConfig bufferConfig() {
        return configuration().getConfigData(BlockBufferConfig.class);
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
}
