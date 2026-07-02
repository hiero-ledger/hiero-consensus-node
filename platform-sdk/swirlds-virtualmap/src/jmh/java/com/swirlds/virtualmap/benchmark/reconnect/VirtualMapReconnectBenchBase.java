// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.benchmark.reconnect;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.DEFAULT_CONFIGURATION;
import static org.hiero.base.utility.test.fixtures.io.ResourceLoader.loadLog4jContext;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapSyncConfig_;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.test.fixtures.datasource.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.sync.ReconnectTestUtils;
import java.io.FileNotFoundException;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.constructable.ConstructableRegistration;

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

    protected final Configuration configuration = ConfigurationBuilder.create()
            .autoDiscoverExtensions()
            // This is lower than the default, helps test that is supposed to fail to finish faster.
            .withValue(VirtualMapSyncConfig_.ASYNC_STREAM_TIMEOUT, "5s")
            .build();

    protected VirtualDataSourceBuilder createBuilder() {
        return new InMemoryBuilder();
    }

    protected void setupEach() {
        teacherBuilder = createBuilder();
        learnerBuilder = createBuilder();
        teacherMap = new VirtualMap(teacherBuilder, DEFAULT_CONFIGURATION);
        learnerMap = new VirtualMap(learnerBuilder, DEFAULT_CONFIGURATION);
    }

    protected static void startup() throws ConstructableRegistryException, FileNotFoundException {
        loadLog4jContext();
        ConstructableRegistration.registerAllConstructables();
    }

    protected void reconnect() throws Exception {
        final VirtualMap copy = teacherMap.copy();
        try {
            final var node = ReconnectTestUtils.testSynchronization(learnerMap, teacherMap, configuration);
            node.release();
            if (!learnerMap.isHashed()) {
                throw new IllegalStateException("Learner root node is not hashed after reconnect");
            }
        } finally {
            teacherMap.release();
            learnerMap.release();
            copy.release();
        }
    }
}
