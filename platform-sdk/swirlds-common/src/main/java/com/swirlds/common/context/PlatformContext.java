// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.context;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.internal.PlatformUncaughtExceptionHandler;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.crypto.MerkleCryptographyFactory;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.Thread.UncaughtExceptionHandler;
import org.hiero.base.concurrent.ExecutorFactory;

/**
 * Public interface of the platform context that provides access to all basic services and resources. By using the
 * {@link PlatformContext} a developer does not need to take care of the lifecycle of any basic service or resource.
 * <p>
 * The basic architecture approach of the {@link PlatformContext} defines the context as a single instance per Platform.
 * When a platform is created the context will be passed to the platform and can be used internally in the platform to
 * access all basic services.
 */
public interface PlatformContext {

    /**
     * Creates a new instance of the platform context. The instance uses a {@link NoOpMetrics} implementation for
     * metrics and a {@link com.swirlds.common.io.utility.NoOpRecycleBin}.
     * The instance uses the static {@link Time#getCurrent()} call to get the time.
     *
     * @apiNote This method is meant for utilities and testing and not for a node's production operation
     * @param configuration the configuration
     * @return the platform context
     */
    @NonNull
    static PlatformContext create(@NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final FileSystemManager fileSystemManager = FileSystemManager.create(configuration);
        final Time time = Time.getCurrent();
        final MerkleCryptography merkleCryptography = MerkleCryptographyFactory.create(configuration);
        return create(configuration, time, metrics, fileSystemManager, new NoOpRecycleBin(), merkleCryptography);
    }

    /**
     * Creates a new instance of the platform context.
     * <p>
     * The instance uses the static {@link Time#getCurrent()} call to get the time.
     *
     * @param configuration     the configuration
     * @param time              the time
     * @param metrics           the metrics
     * @param fileSystemManager the fileSystemManager
     * @param recycleBin        the recycleBin
     * @return the platform context
     */
    @NonNull
    static PlatformContext create(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final Metrics metrics,
            @NonNull final FileSystemManager fileSystemManager,
            @NonNull final RecycleBin recycleBin,
            @NonNull final MerkleCryptography merkleCryptography) {

        final UncaughtExceptionHandler handler = new PlatformUncaughtExceptionHandler();
        final ExecutorFactory executorFactory = ExecutorFactory.create("platform", null, handler);
        return new DefaultPlatformContext(
                configuration, metrics, time, executorFactory, fileSystemManager, recycleBin, merkleCryptography);
    }

    /**
     * Returns the {@link Configuration} instance for the platform
     *
     * @return the {@link Configuration} instance
     */
    @NonNull
    Configuration getConfiguration();

    /**
     * Returns the {@link Metrics} instance for the platform
     *
     * @return the {@link Metrics} instance
     */
    @NonNull
    Metrics getMetrics();

    /**
     * Returns the {@link Time} instance for the platform
     *
     * @return the {@link Time} instance
     */
    @NonNull
    Time getTime();

    /**
     * Returns the {@link FileSystemManager} for this node
     *
     * @return the {@link FileSystemManager} for this node
     */
    @NonNull
    FileSystemManager getFileSystemManager();

    /**
     * Returns the {@link ExecutorFactory} for this node
     *
     * @return the {@link ExecutorFactory} for this node
     */
    @NonNull
    ExecutorFactory getExecutorFactory();

    /**
     * Returns the {@link RecycleBin} for this node
     *
     * @return the {@link RecycleBin} for this node
     */
    @NonNull
    RecycleBin getRecycleBin();

    /**
     * Returns the {@link MerkleCryptography} for this node
     *
     * @return the {@link MerkleCryptography} for this node
     */
    @NonNull
    MerkleCryptography getMerkleCryptography();
}
