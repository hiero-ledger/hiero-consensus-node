// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import org.hiero.base.concurrent.ExecutorFactory;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.noop.NoOpMetrics;

/**
 * Provides singleton access to a configured {@link PlatformContext} instance.
 */
public final class PlatformContextHelper {

    private static PlatformContext platformContext;

    private static PlatformContext createPlatformContext() {
        return new PlatformContext() {

            @Override
            public Configuration getConfiguration() {
                return ConfigUtils.getConfiguration();
            }

            @Override
            public Metrics getMetrics() {
                return new NoOpMetrics();
            }

            @Override
            public Time getTime() {
                return Time.getCurrent();
            }

            @Override
            public ExecutorFactory getExecutorFactory() {
                return null;
            }

            @Override
            public RecycleBin getRecycleBin() {
                return new NoOpRecycleBin();
            }

            @Override
            public FileSystemManager getFileSystemManager() {
                return FileSystemManager.create(ConfigUtils.getConfiguration());
            }
        };
    }

    public static void resetPlatformContext() {
        platformContext = null;
    }

    public static PlatformContext getPlatformContext() {
        if (platformContext == null) {
            platformContext = createPlatformContext();
        }
        return platformContext;
    }
}
