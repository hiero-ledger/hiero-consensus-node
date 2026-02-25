// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.common.test.fixtures.io.ResourceLoader.loadLog4jContext;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSourceBuilder;
import java.io.FileNotFoundException;
import java.io.IOException;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.reconnect.config.ReconnectConfig_;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

public abstract class VirtualMapReconnectTestBase {

    protected VirtualMap teacherMap;
    protected VirtualMap learnerMap;
    protected VirtualDataSourceBuilder teacherBuilder;
    protected VirtualDataSourceBuilder learnerBuilder;

    protected final ReconnectConfig reconnectConfig = new TestConfigBuilder()
            // This is lower than the default, helps test that is supposed to fail to finish faster.
            .withValue(ReconnectConfig_.ASYNC_STREAM_TIMEOUT, "5s")
            .withValue(ReconnectConfig_.MAX_ACK_DELAY, "1000ms")
            .getOrCreateConfig()
            .getConfigData(ReconnectConfig.class);

    protected abstract VirtualDataSourceBuilder createBuilder() throws IOException;

    @BeforeEach
    void setupEach() throws Exception {
        // Some tests set custom default VirtualMap settings, e.g. StreamEventParserTest calls
        // Browser.populateSettingsCommon(). These custom settings can't be used to run VM reconnect
        // tests. As a workaround, set default settings here explicitly
        final VirtualDataSourceBuilder teacherDataSourceBuilder = createBuilder();
        teacherBuilder = teacherDataSourceBuilder;
        final VirtualDataSourceBuilder learnerDataSourceBuilder = createBuilder();
        learnerBuilder = learnerDataSourceBuilder;
        teacherMap = new VirtualMap(teacherBuilder, CONFIGURATION);
        learnerMap = new VirtualMap(learnerBuilder, CONFIGURATION);
    }

    @BeforeAll
    public static void startup() throws ConstructableRegistryException, FileNotFoundException {
        loadLog4jContext();
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero");
    }

    protected void reconnect() throws Exception {
        reconnectMultipleTimes(1);
    }

    protected void reconnectMultipleTimes(int attempts) {
        final VirtualMap copy = teacherMap.copy();
        teacherMap.reserve();
        learnerMap.reserve();
        try {
            for (int i = 0; i < attempts; i++) {
                try {
                    final var node =
                            MerkleTestUtils.hashAndTestSynchronization(learnerMap, teacherMap, reconnectConfig);
                    node.release();
                    assertEquals(attempts - 1, i, "We should only succeed on the last try");
                } catch (Exception e) {
                    if (i == attempts - 1) {
                        fail("We did not expect an exception on this reconnect attempt!", e);
                    }
                }
            }
        } finally {
            teacherMap.release();
            learnerMap.release();
            copy.release();
        }
    }

    @AfterEach
    void tearDown() {
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
