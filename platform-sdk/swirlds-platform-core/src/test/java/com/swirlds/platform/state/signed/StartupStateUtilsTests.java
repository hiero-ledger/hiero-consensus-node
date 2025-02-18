/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.state.signed;

import static com.swirlds.common.test.fixtures.RandomUtils.getRandomPrintSeed;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeSignedStateToDisk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.swirlds.base.time.Time;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.FileUtils;
import com.swirlds.common.io.utility.RecycleBin;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.common.platform.NodeId;
import com.swirlds.common.test.fixtures.TestRecycleBin;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.internal.SignedStateLoadingException;
import com.swirlds.platform.state.PlatformMerkleStateRoot;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import com.swirlds.platform.state.snapshot.StateToDiskReason;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StartupStateUtilities Tests")
public class StartupStateUtilsTests {

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    private SignedStateFilePath signedStateFilePath;

    private final NodeId selfId = NodeId.of(0);
    private final String mainClassName = "mainClassName";
    private final String swirldName = "swirldName";
    private BasicSoftwareVersion currentSoftwareVersion;
    private PlatformStateFacade platformStateFacade;

    @BeforeEach
    void beforeEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        signedStateFilePath = new SignedStateFilePath(new TestConfigBuilder()
                .withValue("state.savedStateDirectory", testDirectory.toString())
                .getOrCreateConfig()
                .getConfigData(StateCommonConfig.class));
        currentSoftwareVersion = new BasicSoftwareVersion(1);
        platformStateFacade = new PlatformStateFacade(v -> currentSoftwareVersion);
    }

    @AfterEach
    void afterEach() throws IOException {
        FileUtils.deleteDirectory(testDirectory);
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds");
        ConstructableRegistry.getInstance()
                .registerConstructable(new ClassConstructorPair(
                        PlatformMerkleStateRoot.class,
                        () -> new PlatformMerkleStateRoot(version -> new BasicSoftwareVersion(version.major()))));
    }

    @NonNull
    private PlatformContext buildContext(final boolean deleteInvalidStateFiles, @NonNull final RecycleBin recycleBin) {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(StateCommonConfig_.SAVED_STATE_DIRECTORY, testDirectory.toString())
                .withValue(StateConfig_.DELETE_INVALID_STATE_FILES, deleteInvalidStateFiles)
                .getOrCreateConfig();

        return TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .withRecycleBin(recycleBin)
                .build();
    }

    /**
     * Write a state to disk in a location that will be discovered by {@link StartupStateUtils}.
     *
     * @return the signed state that was written to disk
     */
    @NonNull
    private SignedState writeState(
            @NonNull final Random random,
            @NonNull final PlatformContext platformContext,
            final long round,
            @Nullable final Hash epoch,
            final boolean corrupted)
            throws IOException {
        MerkleDb.resetDefaultInstancePath();

        final SignedState signedState = new RandomSignedStateGenerator(random)
                .setRound(round)
                .setEpoch(epoch)
                .build();

        // make the state immutable
        signedState.getState().copy();

        final Path savedStateDirectory =
                signedStateFilePath.getSignedStateDirectory(mainClassName, selfId, swirldName, round);

        writeSignedStateToDisk(
                platformContext,
                selfId,
                savedStateDirectory,
                signedState,
                StateToDiskReason.PERIODIC_SNAPSHOT,
                platformStateFacade);

        if (corrupted) {
            final Path stateFilePath = savedStateDirectory.resolve("SignedState.swh");
            Files.delete(stateFilePath);
            final BufferedWriter writer = Files.newBufferedWriter(stateFilePath);
            writer.write("this is not a real state file");
            writer.close();
        }

        return signedState;
    }

    @Test
    @DisplayName("Genesis Test")
    void genesisTest() throws SignedStateLoadingException {
        final PlatformContext platformContext = buildContext(false, TestRecycleBin.getInstance());

        final RecycleBin recycleBin = initializeRecycleBin(platformContext, selfId);

        final SignedState loadedState = StartupStateUtils.loadStateFile(
                        platformContext.getConfiguration(),
                        recycleBin,
                        selfId,
                        mainClassName,
                        swirldName,
                        currentSoftwareVersion,
                        platformStateFacade)
                .getNullable();

        assertNull(loadedState);
    }

    @Test
    @DisplayName("Normal Restart Test")
    void normalRestartTest() throws IOException, SignedStateLoadingException {
        final Random random = getRandomPrintSeed();
        final PlatformContext platformContext = buildContext(false, TestRecycleBin.getInstance());

        int stateCount = 5;

        int latestRound = random.nextInt(1_000, 10_000);
        SignedState latestState = null;
        for (int i = 0; i < stateCount; i++) {
            latestRound += random.nextInt(100, 200);
            latestState = writeState(random, platformContext, latestRound, null, false);
        }

        final RecycleBin recycleBin = initializeRecycleBin(platformContext, selfId);
        MerkleDb.resetDefaultInstancePath();
        final SignedState loadedState = StartupStateUtils.loadStateFile(
                        platformContext.getConfiguration(),
                        recycleBin,
                        selfId,
                        mainClassName,
                        swirldName,
                        currentSoftwareVersion,
                        platformStateFacade)
                .get();

        loadedState.getState().throwIfImmutable();
        loadedState.getState().throwIfDestroyed();

        assertEquals(latestState.getRound(), loadedState.getRound());
        assertEquals(latestState.getState().getHash(), loadedState.getState().getHash());
        RandomSignedStateGenerator.releaseReservable(loadedState.getState());
    }

    @Test
    @DisplayName("Corrupted State No Recycling Test")
    void corruptedStateNoRecyclingTest() throws IOException {
        final Random random = getRandomPrintSeed();
        final PlatformContext platformContext = buildContext(false, TestRecycleBin.getInstance());

        int stateCount = 5;

        int latestRound = random.nextInt(1_000, 10_000);
        for (int i = 0; i < stateCount; i++) {
            latestRound += random.nextInt(100, 200);
            final boolean corrupted = i == stateCount - 1;
            writeState(random, platformContext, latestRound, null, corrupted);
        }
        final RecycleBin recycleBin = initializeRecycleBin(platformContext, selfId);

        assertThrows(SignedStateLoadingException.class, () -> StartupStateUtils.loadStateFile(
                        platformContext.getConfiguration(),
                        recycleBin,
                        selfId,
                        mainClassName,
                        swirldName,
                        currentSoftwareVersion,
                        platformStateFacade)
                .get());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    @DisplayName("Corrupted State Recycling Permitted Test")
    void corruptedStateRecyclingPermittedTest(final int invalidStateCount)
            throws IOException, SignedStateLoadingException {
        final Random random = getRandomPrintSeed();

        final AtomicInteger recycleCount = new AtomicInteger(0);
        final RecycleBin recycleBin = spy(TestRecycleBin.getInstance());
        // increment recycle count every time recycleBin.recycle() is called
        doAnswer(invocation -> {
                    invocation.callRealMethod();
                    recycleCount.incrementAndGet();
                    return null;
                })
                .when(recycleBin)
                .recycle(any());

        final PlatformContext platformContext = buildContext(true, recycleBin);

        int stateCount = 5;

        int latestRound = random.nextInt(1_000, 10_000);
        SignedState latestUncorruptedState = null;
        for (int i = 0; i < stateCount; i++) {
            latestRound += random.nextInt(100, 200);
            final boolean corrupted = (stateCount - i) <= invalidStateCount;
            final SignedState state = writeState(random, platformContext, latestRound, null, corrupted);
            if (!corrupted) {
                latestUncorruptedState = state;
            }
        }
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();

        MerkleDb.resetDefaultInstancePath();
        final SignedState loadedState = StartupStateUtils.loadStateFile(
                        platformContext.getConfiguration(),
                        recycleBin,
                        selfId,
                        mainClassName,
                        swirldName,
                        currentSoftwareVersion,
                        platformStateFacade)
                .getNullable();

        if (latestUncorruptedState != null) {
            loadedState.getState().throwIfImmutable();
            loadedState.getState().throwIfDestroyed();

            assertEquals(latestUncorruptedState.getRound(), loadedState.getRound());
            assertEquals(
                    latestUncorruptedState.getState().getHash(),
                    loadedState.getState().getHash());
        } else {
            assertNull(loadedState);
        }

        if (loadedState != null) {
            RandomSignedStateGenerator.releaseReservable(loadedState.getState());
        }

        final Path savedStateDirectory = signedStateFilePath
                .getSignedStateDirectory(mainClassName, selfId, swirldName, latestRound)
                .getParent();
        int filesCount;
        try (Stream<Path> list = Files.list(savedStateDirectory)) {
            filesCount = (int) list.count();
        }
        assertEquals(5 - invalidStateCount, filesCount, "Unexpected number of files " + filesCount);
        assertEquals(invalidStateCount, recycleCount.get());
    }

    private RecycleBin initializeRecycleBin(PlatformContext platformContext, NodeId selfId) {
        final var metrics = new NoOpMetrics();
        final var configuration = platformContext.getConfiguration();
        final var fileSystemManager = FileSystemManager.create(configuration);
        final var time = Time.getCurrent();
        return RecycleBin.create(metrics, configuration, getStaticThreadManager(), time, fileSystemManager, selfId);
    }
}
