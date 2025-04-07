// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.utils;

import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.metrics.api.Metrics;
import java.util.Random;
import java.util.concurrent.Executors;
import org.hiero.consensus.model.node.NodeId;

public class TestUtils {
    private static final Random RANDOM = new Random(9239992);
    private static final long DEFAULT_NODE_ID = 3;

    /**
     * Generates some random bytes
     *
     * @param length The number of bytes to generate.
     * @return Some random bytes.
     */
    public static byte[] randomBytes(final int length) {
        final byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) RANDOM.nextInt();
        }
        return data;
    }

    public static Metrics metrics() {
        final MetricsConfig metricsConfig =
                HederaTestConfigBuilder.createConfig().getConfigData(MetricsConfig.class);

        return new DefaultPlatformMetrics(
                NodeId.of(DEFAULT_NODE_ID),
                new MetricKeyRegistry(),
                Executors.newSingleThreadScheduledExecutor(),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }
}
