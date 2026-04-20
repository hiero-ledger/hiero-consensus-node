// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.hedera.node.app.blocks.impl.streaming.config.BlockNodeConfiguration;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfiguration;
import com.hedera.node.config.data.BlockNodeConnectionConfig;
import com.swirlds.common.io.utility.FileUtils;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BlockNodeConfigServiceTest extends BlockNodeCommunicationTestBase {

    private static final VarHandle watchServiceRefHandle;
    private static final VarHandle isActiveHandle;
    private static final VarHandle latestConfigRefHandle;
    private static final VarHandle configVersionCounterHandle;
    private static final MethodHandle loadConfigurationHandle;

    static {
        try {
            final Lookup lookup = MethodHandles.privateLookupIn(BlockNodeConfigService.class, MethodHandles.lookup());

            isActiveHandle = lookup.findVarHandle(BlockNodeConfigService.class, "isActive", AtomicBoolean.class);
            watchServiceRefHandle =
                    lookup.findVarHandle(BlockNodeConfigService.class, "watchServiceRef", AtomicReference.class);
            latestConfigRefHandle =
                    lookup.findVarHandle(BlockNodeConfigService.class, "latestConfigRef", AtomicReference.class);
            configVersionCounterHandle =
                    lookup.findVarHandle(BlockNodeConfigService.class, "configVersionCounter", AtomicInteger.class);

            final Method loadConfiguration = BlockNodeConfigService.class.getDeclaredMethod("loadConfiguration");
            loadConfiguration.setAccessible(true);
            loadConfigurationHandle = lookup.unreflect(loadConfiguration);
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final String BLOCK_NODES_FILE_NAME = "block-nodes.json";

    @TempDir
    private Path configDir;

    private ConfigProvider configProvider;
    private BlockNodeConfigService configService;
    private BlockNodeConnectionConfig bncConfig;

    @BeforeEach
    void beforeEach() {
        bncConfig = mock(BlockNodeConnectionConfig.class);
        configProvider = mock(ConfigProvider.class);
        final VersionedConfiguration versionedConfiguration = mock(VersionedConfiguration.class);

        lenient().when(bncConfig.blockNodeConnectionFileDir()).thenReturn(configDir.toString());
        lenient().when(bncConfig.defaultMessageHardLimitBytes()).thenReturn(37748736L);
        lenient().when(configProvider.getConfiguration()).thenReturn(versionedConfiguration);
        lenient()
                .when(versionedConfiguration.getConfigData(BlockNodeConnectionConfig.class))
                .thenReturn(bncConfig);

        configService = new BlockNodeConfigService(configProvider);
    }

    @AfterEach
    void afterEach() {
        if (configService != null) {
            configService.shutdown();
        }
    }

    @Test
    void testStart_alreadyStarted() {
        assertThat(watchServiceRef()).hasNullValue();

        // set the isActive flag to true
        isActive().set(true);

        // the start should exit fast because isActive is already set to true
        configService.start();

        // since the service is already "started" the watcher will not be initialized
        assertThat(watchServiceRef()).hasNullValue();
    }

    @Test
    void testStart_initLoadFailure() throws IOException {
        writeConfig("bad!!!");

        assertThat(watchServiceRef()).hasNullValue();

        configService.start();

        // a bad config was initially loaded so the latest config should be null but the watcher should still be created
        assertThat(latestConfigRef()).hasNullValue();
        assertThat(watchServiceRef()).doesNotHaveNullValue();
    }

    @Test
    void testStart_watcherFailure() throws Exception {
        try (final MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            final Path configDirectory = mock(Path.class);
            final FileSystem fileSystem = mock(FileSystem.class);
            when(configDirectory.getFileSystem()).thenReturn(fileSystem);
            doThrow(new IOException("why do things break all the time?"))
                    .when(fileSystem)
                    .newWatchService();
            mockedFileUtils.when(() -> FileUtils.getAbsolutePath(anyString())).thenReturn(configDirectory);

            configService = new BlockNodeConfigService(configProvider);
            configService.start();

            // since the watcher creation failed, the service should not be active and no watcher ref should be set
            assertThat(isActive()).isFalse();
            assertThat(watchServiceRef()).hasNullValue();

            verify(fileSystem).newWatchService();
        }
    }

    @Test
    void testStart() throws Exception {
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        }
                    ]
                }
                """);

        configService.start();

        assertThat(isActive()).isTrue();
        assertThat(watchServiceRef()).doesNotHaveNullValue();

        final VersionedBlockNodeConfigurationSet config = configService.latestConfiguration();
        assertThat(config).isNotNull();
        assertThat(config.versionNumber()).isEqualTo(1L);
        assertThat(config.configs()).hasSize(1);
        final BlockNodeConfiguration nodeConfig = config.configs().getFirst();
        assertThat(nodeConfig).isNotNull();
        assertThat(nodeConfig.address()).isEqualTo("localhost");
        assertThat(nodeConfig.streamingPort()).isEqualTo(9999);
        assertThat(nodeConfig.priority()).isEqualTo(1);
    }

    @Test
    void testShutdown_alreadyStopped() {
        final WatchService watchService = mock(WatchService.class);
        watchServiceRef().set(watchService); // set the watch service so we can confirm the early exit from #stop
        isActive().set(false);

        configService.shutdown();

        assertThat(isActive()).isFalse();
        assertThat(watchServiceRef()).doesNotHaveNullValue().hasValue(watchService);
    }

    @Test
    void testShutdown_watcherCloseFailure() throws Exception {
        final WatchService watchService = mock(WatchService.class);
        doThrow(new IOException("why am I such a failure?")).when(watchService).close();

        watchServiceRef().set(watchService);
        isActive().set(true);

        configService.shutdown();

        verify(watchService).close();

        // regardless of the WatchService#close failure, the active flag should be set to false and the WatchService
        // reference should be cleared
        assertThat(isActive()).isFalse();
        assertThat(watchServiceRef()).hasNullValue();
    }

    @Test
    void testShutdown() throws Exception {
        final WatchService watchService = mock(WatchService.class);
        final VersionedBlockNodeConfigurationSet config = new VersionedBlockNodeConfigurationSet(1, List.of());

        watchServiceRef().set(watchService);
        latestConfigRef().set(config);
        configVersionCounter().set(1);
        isActive().set(true);

        configService.shutdown();

        verify(watchService).close();

        assertThat(watchServiceRef()).hasNullValue();
        assertThat(latestConfigRef()).hasNullValue();
        // a shutdown is treated as a config change since the config is removed, thus the counter is incremented
        assertThat(configVersionCounter()).hasValue(2);
    }

    @Test
    void testLatestConfig() {
        final AtomicReference<VersionedBlockNodeConfigurationSet> latestConfigRef = latestConfigRef();
        final VersionedBlockNodeConfigurationSet config = new VersionedBlockNodeConfigurationSet(1, List.of());
        latestConfigRef.set(config);

        assertThat(configService.latestConfiguration()).isNotNull().isEqualTo(config);
    }

    @Test
    void testLoadConfiguration_pathNotExists() throws Throwable {
        // don't write a configuration file
        invoke_loadConfiguration();

        // since there is no configuration file, there should be no configuration set
        assertThat(latestConfigRef()).hasNullValue();
    }

    @Test
    void testLoadConfiguration_parseFailure() throws Throwable {
        writeConfig("""
                [{]
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        }
                    ]
                }
                """);

        invoke_loadConfiguration();

        // since the config file is bad, there should be no configuration set
        assertThat(latestConfigRef()).hasNullValue();
    }

    @Test
    void testLoadConfiguration_singleNodeParseFailure() throws Throwable {
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        },
                        {
                            "address": "localhost",
                            "streamingPort": 9998,
                            "priority": -1
                        }
                    ]
                }
                """);

        invoke_loadConfiguration();

        final VersionedBlockNodeConfigurationSet config = configService.latestConfiguration();
        assertThat(config).isNotNull();
        assertThat(config.versionNumber()).isEqualTo(1L);
        assertThat(config.configs()).hasSize(1);
        final BlockNodeConfiguration nodeConfig = config.configs().getFirst();
        assertThat(nodeConfig).isNotNull();
        assertThat(nodeConfig.address()).isEqualTo("localhost");
        assertThat(nodeConfig.streamingPort()).isEqualTo(9999);
        assertThat(nodeConfig.priority()).isEqualTo(1);
    }

    @Test
    void testLoadConfiguration_emptyConfigs() throws Throwable {
        // write an empty config file
        writeConfig("""
                {
                    "nodes": [
                    ]
                }
                """);

        invoke_loadConfiguration();

        final VersionedBlockNodeConfigurationSet config = configService.latestConfiguration();
        assertThat(config).isNotNull();
        assertThat(config.versionNumber()).isEqualTo(1L);
        assertThat(config.configs()).isEmpty();
    }

    @Test
    void testLoadConfiguration_noSuccessfulConfigsFound() throws Throwable {
        // start with a good configuration
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        },
                        {
                            "address": "localhost",
                            "streamingPort": 9998,
                            "priority": 2
                        }
                    ]
                }
                """);

        invoke_loadConfiguration();

        final VersionedBlockNodeConfigurationSet initialConfig = configService.latestConfiguration();
        assertThat(initialConfig).isNotNull();
        assertThat(initialConfig.versionNumber()).isEqualTo(1L);
        assertThat(initialConfig.configs()).hasSize(2);
        assertThat(configVersionCounter()).hasValue(1);

        for (final BlockNodeConfiguration nodeConfig : initialConfig.configs()) {
            if (nodeConfig.streamingPort() == 9999) {
                assertThat(nodeConfig).isNotNull();
                assertThat(nodeConfig.address()).isEqualTo("localhost");
                assertThat(nodeConfig.streamingPort()).isEqualTo(9999);
                assertThat(nodeConfig.priority()).isEqualTo(1);
            } else if (nodeConfig.streamingPort() == 9998) {
                assertThat(nodeConfig).isNotNull();
                assertThat(nodeConfig.address()).isEqualTo("localhost");
                assertThat(nodeConfig.streamingPort()).isEqualTo(9998);
                assertThat(nodeConfig.priority()).isEqualTo(2);
            } else {
                fail("Unexpected configuration found:" + nodeConfig);
            }
        }

        // break both configs and try to reload
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": -10,
                            "priority": 1
                        },
                        {
                            "address": "localhost",
                            "streamingPort": -11,
                            "priority": 2
                        }
                    ]
                }
                """);

        invoke_loadConfiguration();

        // since all configurations are broken, the configuration as a whole is rejected and thus the latest
        // configuration should not be changed
        final VersionedBlockNodeConfigurationSet config = configService.latestConfiguration();
        assertThat(config).isNotNull().isEqualTo(initialConfig);
        assertThat(configVersionCounter()).hasValue(1);
    }

    @Test
    void testLoadConfiguration_duplicateConfigsFound() throws Throwable {
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        },
                        {
                            "address": "localhost",
                            "streamingPort": 9998,
                            "priority": 2
                        },
                        {
                            "address": "localhost",
                            "streamingPort": 9998,
                            "priority": 3
                        }
                    ]
                }
                """);

        invoke_loadConfiguration();

        final VersionedBlockNodeConfigurationSet config = configService.latestConfiguration();
        assertThat(config).isNull();
        assertThat(configVersionCounter()).hasValue(0); // no config is loaded
    }

    @Test
    void testLoadConfiguration() throws Throwable {
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        },
                        {
                            "address": "localhost",
                            "streamingPort": 9998,
                            "priority": 2
                        }
                    ]
                }
                """);

        invoke_loadConfiguration();

        final VersionedBlockNodeConfigurationSet config = configService.latestConfiguration();
        assertThat(config).isNotNull();
        assertThat(config.versionNumber()).isEqualTo(1L);
        assertThat(config.configs()).hasSize(2);

        for (final BlockNodeConfiguration nodeConfig : config.configs()) {
            if (nodeConfig.streamingPort() == 9999) {
                assertThat(nodeConfig).isNotNull();
                assertThat(nodeConfig.address()).isEqualTo("localhost");
                assertThat(nodeConfig.streamingPort()).isEqualTo(9999);
                assertThat(nodeConfig.priority()).isEqualTo(1);
            } else if (nodeConfig.streamingPort() == 9998) {
                assertThat(nodeConfig).isNotNull();
                assertThat(nodeConfig.address()).isEqualTo("localhost");
                assertThat(nodeConfig.streamingPort()).isEqualTo(9998);
                assertThat(nodeConfig.priority()).isEqualTo(2);
            } else {
                fail("Unexpected configuration found:" + nodeConfig);
            }
        }
    }

    @Test
    void testConfigWatcher_onCreate() throws Exception {
        // don't write an initial configuration
        configService.start();

        final VersionedBlockNodeConfigurationSet initialConfig = configService.latestConfiguration();
        assertThat(initialConfig).isNull();

        // create a new configuration file
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        }
                    ]
                }
                """);

        final long waitingMillisStart = System.currentTimeMillis();
        VersionedBlockNodeConfigurationSet updatedConfig = configService.latestConfiguration();

        // wait for the new configuration to be detected and loaded
        while (updatedConfig == null) {
            if ((System.currentTimeMillis() - waitingMillisStart) >= 10_000) {
                fail("Waited 10 seconds for configuration update, but it never happened");
            }

            Thread.sleep(100);
            updatedConfig = configService.latestConfiguration();
        }

        final BlockNodeConfiguration updatedConfigNode1 =
                updatedConfig.configs().getFirst();
        assertThat(updatedConfigNode1).isNotNull();
        assertThat(updatedConfigNode1.address()).isEqualTo("localhost");
        assertThat(updatedConfigNode1.streamingPort()).isEqualTo(9999);
        assertThat(updatedConfigNode1.priority()).isEqualTo(1);
    }

    @Test
    void testConfigWatcher_onUpdate() throws Exception {
        // create initial configuration
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        }
                    ]
                }
                """);

        configService.start();

        final VersionedBlockNodeConfigurationSet initialConfig = configService.latestConfiguration();
        assertThat(initialConfig).isNotNull();
        assertThat(initialConfig.versionNumber()).isEqualTo(1L);

        final BlockNodeConfiguration initialConfigNode1 =
                initialConfig.configs().getFirst();
        assertThat(initialConfigNode1).isNotNull();
        assertThat(initialConfigNode1.address()).isEqualTo("localhost");
        assertThat(initialConfigNode1.streamingPort()).isEqualTo(9999);
        assertThat(initialConfigNode1.priority()).isEqualTo(1);

        // update the configuration
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        },
                        {
                            "address": "localhost",
                            "streamingPort": 9998,
                            "priority": 2
                        }
                    ]
                }
                """);

        long waitingMillisStart = System.currentTimeMillis();
        VersionedBlockNodeConfigurationSet updatedConfig = configService.latestConfiguration();

        // wait for the new configuration to be detected and loaded
        while (updatedConfig == null || updatedConfig.versionNumber() <= initialConfig.versionNumber()) {
            if ((System.currentTimeMillis() - waitingMillisStart) >= 10_000) {
                fail("Waited 10 seconds for configuration update, but it never happened");
            }

            Thread.sleep(100);
            updatedConfig = configService.latestConfiguration();
        }

        for (final BlockNodeConfiguration nodeConfig : updatedConfig.configs()) {
            if (nodeConfig.streamingPort() == 9999) {
                assertThat(nodeConfig).isNotNull();
                assertThat(nodeConfig.address()).isEqualTo("localhost");
                assertThat(nodeConfig.streamingPort()).isEqualTo(9999);
                assertThat(nodeConfig.priority()).isEqualTo(1);
            } else if (nodeConfig.streamingPort() == 9998) {
                assertThat(nodeConfig).isNotNull();
                assertThat(nodeConfig.address()).isEqualTo("localhost");
                assertThat(nodeConfig.streamingPort()).isEqualTo(9998);
                assertThat(nodeConfig.priority()).isEqualTo(2);
            } else {
                fail("Unexpected configuration found:" + nodeConfig);
            }
        }

        // update the configuration again to clear out the contents
        writeConfig("""
                {
                    "nodes": [
                    ]
                }
                """);

        waitingMillisStart = System.currentTimeMillis();
        VersionedBlockNodeConfigurationSet updatedConfig2 = configService.latestConfiguration();

        // wait for the new configuration to be detected and loaded
        while (updatedConfig2 == null || updatedConfig2.versionNumber() <= updatedConfig.versionNumber()) {
            if ((System.currentTimeMillis() - waitingMillisStart) >= 10_000) {
                fail("Waited 10 seconds for configuration update, but it never happened");
            }

            Thread.sleep(100);
            updatedConfig2 = configService.latestConfiguration();
        }

        assertThat(updatedConfig2).isNotNull();
        assertThat(updatedConfig2.versionNumber()).isGreaterThan(updatedConfig.versionNumber());
        assertThat(updatedConfig2.configs()).isEmpty();
    }

    @Test
    void testConfigWatcher_onDelete() throws Exception {
        // create initial configuration
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        }
                    ]
                }
                """);

        configService.start();

        final VersionedBlockNodeConfigurationSet initialConfig = configService.latestConfiguration();
        assertThat(initialConfig).isNotNull();
        assertThat(initialConfig.versionNumber()).isEqualTo(1L);

        // delete the config file
        final Path filePath = configDir.resolve(BLOCK_NODES_FILE_NAME);
        assertThat(Files.deleteIfExists(filePath)).isTrue();

        final long waitingMillisStart = System.currentTimeMillis();
        VersionedBlockNodeConfigurationSet postDeleteConfig = configService.latestConfiguration();
        assertThat(postDeleteConfig).isNotNull();

        // wait for the configuration deletion to be detected
        while (initialConfig.versionNumber() >= postDeleteConfig.versionNumber()) {
            if ((System.currentTimeMillis() - waitingMillisStart) >= 10_000) {
                fail("Waited 10 seconds for configuration update, but it never happened");
            }

            Thread.sleep(100);
            postDeleteConfig = configService.latestConfiguration();
            assertThat(postDeleteConfig).isNotNull();
        }

        assertThat(postDeleteConfig).isNotNull();
        assertThat(postDeleteConfig.configs()).isEmpty();

        // add the configuration back and make sure it is loaded successfully
        writeConfig("""
                {
                    "nodes": [
                        {
                            "address": "localhost",
                            "streamingPort": 9999,
                            "priority": 1
                        }
                    ]
                }
                """);

        VersionedBlockNodeConfigurationSet postCreateConfig = configService.latestConfiguration();
        assertThat(postCreateConfig).isNotNull();

        // wait for the configuration update to be detected
        while (postCreateConfig.versionNumber() <= postDeleteConfig.versionNumber()) {
            if ((System.currentTimeMillis() - waitingMillisStart) >= 10_000) {
                fail("Waited 10 seconds for configuration update, but it never happened");
            }

            Thread.sleep(100);
            postCreateConfig = configService.latestConfiguration();
            assertThat(postCreateConfig).isNotNull();
        }

        // the previous delete operation increments the version counter, so once the new config is updated its version
        // will be set to 3 instead of 2
        assertThat(postCreateConfig.versionNumber()).isGreaterThan(postDeleteConfig.versionNumber());
    }

    @Test
    void testConfigWatcher_watchServiceClosed() throws Exception {
        try (final MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            final Path configDirectory = mock(Path.class);
            final FileSystem fileSystem = mock(FileSystem.class);
            final WatchService watchService = mock(WatchService.class);
            when(configDirectory.getFileSystem()).thenReturn(fileSystem);
            when(fileSystem.newWatchService()).thenReturn(watchService);
            doThrow(new ClosedWatchServiceException()).when(watchService).take();

            final CountDownLatch latch = new CountDownLatch(1);
            doAnswer(_ -> {
                        latch.countDown();
                        return null;
                    })
                    .when(watchService)
                    .close();

            mockedFileUtils.when(() -> FileUtils.getAbsolutePath(anyString())).thenReturn(configDirectory);

            configService = new BlockNodeConfigService(configProvider);
            configService.start();

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // since the watcher creation failed, the service should not be active and no watcher ref should be set
            assertThat(isActive()).isFalse();
            assertThat(watchServiceRef()).hasNullValue();

            verify(fileSystem).newWatchService();
            verify(watchService).take();
            verify(watchService).close();
        }
    }

    @Test
    void testConfigWatcher_randomError() throws Exception {
        try (final MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            final Path configDirectory = mock(Path.class);
            final FileSystem fileSystem = mock(FileSystem.class);
            final WatchService watchService = mock(WatchService.class);
            final WatchKey watchKey = mock(WatchKey.class);
            lenient().when(watchKey.pollEvents()).thenReturn(List.of());
            lenient().when(watchKey.reset()).thenReturn(true);
            when(configDirectory.getFileSystem()).thenReturn(fileSystem);
            when(fileSystem.newWatchService()).thenReturn(watchService);

            final AtomicBoolean isFirstTime = new AtomicBoolean(true);
            final CountDownLatch latch = new CountDownLatch(1);
            doAnswer(_ -> {
                        latch.countDown();
                        if (isFirstTime.compareAndSet(true, false)) {
                            // only throw an exception on the first attempt
                            throw new RuntimeException("this isn't how it all ends");
                        }

                        return watchKey;
                    })
                    .when(watchService)
                    .take();

            mockedFileUtils.when(() -> FileUtils.getAbsolutePath(anyString())).thenReturn(configDirectory);

            configService = new BlockNodeConfigService(configProvider);
            configService.start();

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // there was a non-fatal error so the service should still be active and the watcher running
            assertThat(isActive()).isTrue();
            assertThat(watchServiceRef()).doesNotHaveNullValue();

            verify(fileSystem).newWatchService();
            verify(watchService, atLeastOnce()).take();
            verifyNoMoreInteractions(watchService);
        }
    }

    @Test
    void testConfigWatcher_keyResetFailed() throws Exception {
        try (final MockedStatic<FileUtils> mockedFileUtils = mockStatic(FileUtils.class)) {
            final Path configDirectory = mock(Path.class);
            final FileSystem fileSystem = mock(FileSystem.class);
            final WatchService watchService = mock(WatchService.class);
            final WatchKey watchKey = mock(WatchKey.class);
            when(watchKey.pollEvents()).thenReturn(List.of());
            when(watchKey.reset()).thenReturn(false);
            when(watchService.take()).thenReturn(watchKey);
            when(configDirectory.getFileSystem()).thenReturn(fileSystem);
            when(fileSystem.newWatchService()).thenReturn(watchService);

            final CountDownLatch latch = new CountDownLatch(1);
            doAnswer(_ -> {
                        latch.countDown();
                        return null;
                    })
                    .when(watchService)
                    .close();

            mockedFileUtils.when(() -> FileUtils.getAbsolutePath(anyString())).thenReturn(configDirectory);

            configService = new BlockNodeConfigService(configProvider);
            configService.start();

            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();

            // there was a fatal error so the service should be stopped and the watcher removed
            assertThat(isActive()).isFalse();
            assertThat(watchServiceRef()).hasNullValue();

            verify(fileSystem).newWatchService();
            verify(watchService).take();
            verify(watchService).close();
            verify(watchKey).reset();
            verifyNoMoreInteractions(watchService);
        }
    }

    // Utilities =========

    void invoke_loadConfiguration() throws Throwable {
        loadConfigurationHandle.invoke(configService);
    }

    void writeConfig(final String config) throws IOException {
        final Path filePath = configDir.resolve(BLOCK_NODES_FILE_NAME);
        Files.writeString(filePath, config);
    }

    AtomicInteger configVersionCounter() {
        return (AtomicInteger) configVersionCounterHandle.get(configService);
    }

    AtomicBoolean isActive() {
        return (AtomicBoolean) isActiveHandle.get(configService);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<WatchService> watchServiceRef() {
        return (AtomicReference<WatchService>) watchServiceRefHandle.get(configService);
    }

    @SuppressWarnings("unchecked")
    AtomicReference<VersionedBlockNodeConfigurationSet> latestConfigRef() {
        return (AtomicReference<VersionedBlockNodeConfigurationSet>) latestConfigRefHandle.get(configService);
    }
}
