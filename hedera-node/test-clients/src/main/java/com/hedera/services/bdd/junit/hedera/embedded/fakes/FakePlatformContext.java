// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.common.concurrent.ExecutorFactory;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.config.CryptoConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.config.TransactionConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.consensus.model.node.NodeId;

public class FakePlatformContext implements PlatformContext {
    public static final Configuration PLATFORM_CONFIG = ConfigurationBuilder.create()
            .withConfigDataType(MetricsConfig.class)
            .withConfigDataType(TransactionConfig.class)
            .withConfigDataType(CryptoConfig.class)
            .withConfigDataType(BasicConfig.class)
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(MerkleDbConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .build();

    private final Metrics metrics;

    public FakePlatformContext(
            @NonNull final NodeId defaultNodeId,
            @NonNull final ScheduledExecutorService executorService,
            @NonNull final Metrics metrics) {
        requireNonNull(defaultNodeId);
        requireNonNull(executorService);
        this.metrics = requireNonNull(metrics);
    }

    @NonNull
    @Override
    public Configuration getConfiguration() {
        return PLATFORM_CONFIG;
    }

    @NonNull
    @Override
    public Metrics getMetrics() {
        return metrics;
    }

    @NonNull
    @Override
    public Time getTime() {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @NonNull
    @Override
    public FileSystemManager getFileSystemManager() {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @NonNull
    @Override
    public ExecutorFactory getExecutorFactory() {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @NonNull
    @Override
    public RecycleBin getRecycleBin() {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @NonNull
    @Override
    public MerkleCryptography getMerkleCryptography() {
        return MerkleCryptographyFactory.create(PLATFORM_CONFIG);
    }
}
