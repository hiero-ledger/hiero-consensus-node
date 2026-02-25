// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.benchmark.reconnect;

import static com.swirlds.common.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;

import com.swirlds.common.merkle.synchronization.task.QueryResponse;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import java.io.FileNotFoundException;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.reconnect.config.ReconnectConfig_;
import org.junit.jupiter.api.Assertions;

/**
 * The code is partially borrowed from VirtualMapReconnectTestBase.java in swirlds-virtualmap/src/test/.
 * Ideally, it belongs to a shared test fixture, but I was unable to find a way to resolve dependencies
 * between projects and modules, so I created this copy here and removed a few static definitions that
 * are irrelevant to JMH benchmarks. In the future, this JMH-specific copy may in fact diverge
 * from the unit test base class if/when we implement performance testing-related features here
 * (e.g. artificial latencies etc.)
 */
public abstract class VirtualMapReconnectBenchBase {

    protected VirtualMap teacherMap;
    protected VirtualMap learnerMap;
    protected VirtualDataSourceBuilder teacherBuilder;
    protected VirtualDataSourceBuilder learnerBuilder;

    protected final ReconnectConfig reconnectConfig = new TestConfigBuilder()
            // This is lower than the default, helps test that is supposed to fail to finish faster.
            .withValue(ReconnectConfig_.ASYNC_STREAM_TIMEOUT, "5s")
            .getOrCreateConfig()
            .getConfigData(ReconnectConfig.class);

    protected VirtualDataSourceBuilder createBuilder() {
        return new InMemoryBuilder();
    }

    protected void setupEach() {
        teacherBuilder = createBuilder();
        learnerBuilder = createBuilder();
        teacherMap = new VirtualMap(teacherBuilder, CONFIGURATION);
        learnerMap = new VirtualMap(learnerBuilder, CONFIGURATION);
    }

    protected static void startup() throws ConstructableRegistryException, FileNotFoundException {
        loadLog4jContext();
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructable(new ClassConstructorPair(QueryResponse.class, QueryResponse::new));
    }

    protected void reconnect() throws Exception {
        final VirtualMap copy = teacherMap.copy();
        try {
            final var node = MerkleTestUtils.hashAndTestSynchronization(learnerMap, teacherMap, reconnectConfig);
            node.release();
            Assertions.assertTrue(learnerMap.isHashed(), "Learner root node must be hashed");
        } finally {
            teacherMap.release();
            learnerMap.release();
            copy.release();
        }
    }
}
