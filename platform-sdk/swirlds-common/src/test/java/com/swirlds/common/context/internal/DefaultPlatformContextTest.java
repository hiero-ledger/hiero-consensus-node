// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.context.internal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import com.swirlds.base.time.Time;
import com.swirlds.common.concurrent.ExecutorFactory;
import com.swirlds.common.context.DefaultPlatformContext;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.metrics.PlatformMetricsProvider;
import com.swirlds.common.metrics.platform.DefaultMetricsProvider;
import com.swirlds.common.test.fixtures.TestFileSystemManager;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.nio.file.Path;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.Test;

class DefaultPlatformContextTest {

    @Test
    void testNoNullServices() {
        // given
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final NodeId nodeId = NodeId.of(3256733545L);
        final PlatformMetricsProvider metricsProvider = new DefaultMetricsProvider(configuration);
        metricsProvider.createGlobalMetrics();
        final MerkleCryptography merkleCryptography = mock(MerkleCryptography.class);

        // when
        final PlatformContext context = new DefaultPlatformContext(
                configuration,
                metricsProvider.createPlatformMetrics(nodeId),
                Time.getCurrent(),
                ExecutorFactory.create("test", new PlatformUncaughtExceptionHandler()),
                new TestFileSystemManager(Path.of("/tmp/test")),
                new NoOpRecycleBin(),
                merkleCryptography);

        // then
        assertNotNull(context.getConfiguration(), "Configuration must not be null");
        assertNotNull(context.getMetrics(), "Metrics must not be null");
        assertNotNull(context.getTime(), "Time must not be null");
        assertNotNull(context.getFileSystemManager(), "FileSystemManager must not be null");
        assertNotNull(context.getExecutorFactory(), "ExecutorFactory must not be null");
        assertNotNull(context.getMerkleCryptography(), "MerkleCryptography must not be null");
    }
}
