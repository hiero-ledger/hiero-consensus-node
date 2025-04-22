// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.benchmark.reconnect;

import static com.swirlds.common.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.common.merkle.synchronization.task.QueryResponse;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleInternal;
import com.swirlds.common.test.fixtures.merkle.dummy.DummyMerkleLeaf;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import com.swirlds.virtualmap.internal.merkle.VirtualMapState;
import com.swirlds.virtualmap.internal.merkle.VirtualRootNode;
import com.swirlds.virtualmap.internal.pipeline.VirtualRoot;
import com.swirlds.virtualmap.test.fixtures.InMemoryBuilder;
import com.swirlds.virtualmap.test.fixtures.TestKey;
import com.swirlds.virtualmap.test.fixtures.TestKeySerializer;
import com.swirlds.virtualmap.test.fixtures.TestValue;
import com.swirlds.virtualmap.test.fixtures.TestValueSerializer;
import java.io.FileNotFoundException;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
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

    protected VirtualMap<TestKey, TestValue> teacherMap;
    protected VirtualMap<TestKey, TestValue> learnerMap;
    protected VirtualDataSourceBuilder teacherBuilder;
    protected VirtualDataSourceBuilder learnerBuilder;

    protected final ReconnectConfig reconnectConfig = new TestConfigBuilder()
            // This is lower than the default, helps test that is supposed to fail to finish faster.
            .withValue(ReconnectConfig_.ASYNC_STREAM_TIMEOUT, "5s")
            .withValue(ReconnectConfig_.MAX_ACK_DELAY, "1000ms")
            .getOrCreateConfig()
            .getConfigData(ReconnectConfig.class);

    protected VirtualDataSourceBuilder createBuilder() {
        return new InMemoryBuilder();
    }

    protected void setupEach() {
        teacherBuilder = createBuilder();
        learnerBuilder = createBuilder();
        teacherMap = new VirtualMap<>(
                "Teacher", TestKeySerializer.INSTANCE, TestValueSerializer.INSTANCE, teacherBuilder, CONFIGURATION);
        learnerMap = new VirtualMap<>(
                "Learner", TestKeySerializer.INSTANCE, TestValueSerializer.INSTANCE, learnerBuilder, CONFIGURATION);
    }

    protected static void startup() throws ConstructableRegistryException, FileNotFoundException {
        loadLog4jContext();
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero.consensus");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructable(new ClassConstructorPair(QueryResponse.class, QueryResponse::new));
        registry.registerConstructable(new ClassConstructorPair(DummyMerkleInternal.class, DummyMerkleInternal::new));
        registry.registerConstructable(new ClassConstructorPair(DummyMerkleLeaf.class, DummyMerkleLeaf::new));
        registry.registerConstructable(new ClassConstructorPair(VirtualMap.class, () -> new VirtualMap(CONFIGURATION)));
        registry.registerConstructable(new ClassConstructorPair(VirtualMapState.class, VirtualMapState::new));
        registry.registerConstructable(new ClassConstructorPair(
                VirtualRootNode.class,
                () -> new VirtualRootNode<>(CONFIGURATION.getConfigData(VirtualMapConfig.class))));
        registry.registerConstructable(new ClassConstructorPair(TestKey.class, TestKey::new));
        registry.registerConstructable(new ClassConstructorPair(TestValue.class, TestValue::new));
    }

    protected MerkleInternal createTreeForMap(VirtualMap<TestKey, TestValue> map) {
        final var tree = MerkleTestUtils.buildLessSimpleTree();
        tree.getChild(1).asInternal().setChild(3, map);
        tree.reserve();
        return tree;
    }

    protected void reconnect() throws Exception {
        final MerkleInternal teacherTree = createTreeForMap(teacherMap);
        final VirtualMap<TestKey, TestValue> copy = teacherMap.copy();
        final MerkleInternal learnerTree = createTreeForMap(learnerMap);
        try {
            final var node = MerkleTestUtils.hashAndTestSynchronization(learnerTree, teacherTree, reconnectConfig);
            node.release();
            final VirtualRoot root = learnerMap.getRight();
            Assertions.assertTrue(root.isHashed(), "Learner root node must be hashed");
        } finally {
            teacherTree.release();
            learnerTree.release();
            copy.release();
        }
    }
}
