// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readStateFile;
import static com.swirlds.platform.state.snapshot.StateToDiskReason.FATAL_ERROR;
import static com.swirlds.platform.state.snapshot.StateToDiskReason.ISS;
import static com.swirlds.platform.state.snapshot.StateToDiskReason.PERIODIC_SNAPSHOT;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer.registerMerkleStateRootClassIds;
import static java.nio.file.Files.exists;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.components.DefaultSavedStateController;
import com.swirlds.platform.components.SavedStateController;
import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.snapshot.DefaultStateSnapshotManager;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SavedStateInfo;
import com.swirlds.platform.state.snapshot.SavedStateMetadata;
import com.swirlds.platform.state.snapshot.SignedStateFilePath;
import com.swirlds.platform.state.snapshot.SignedStateFileReader;
import com.swirlds.platform.state.snapshot.SignedStateFileUtils;
import com.swirlds.platform.state.snapshot.StateDumpRequest;
import com.swirlds.platform.state.snapshot.StateSnapshotManager;
import com.swirlds.platform.test.fixtures.state.BlockingState;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestHederaVirtualMapState;
import com.swirlds.platform.test.fixtures.state.TestingAppStateInitializer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.hiero.base.CompareTo;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.state.StateSavingResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StateFileManagerTests {

    private static final NodeId SELF_ID = NodeId.of(1234);
    private static final String MAIN_CLASS_NAME = "com.swirlds.foobar";
    private static final String SWIRLD_NAME = "mySwirld";

    private PlatformContext context;
    private SignedStateFilePath signedStateFilePath;

    Path testDirectory;

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero");
        registerMerkleStateRootClassIds();
    }

    @BeforeEach
    void beforeEach() throws IOException {
        testDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile(
                "SignedStateFileReadWriteTest", TestingAppStateInitializer.CONFIGURATION);
        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(testDirectory);
        final TestConfigBuilder configBuilder = new TestConfigBuilder()
                .withValue(
                        StateCommonConfig_.SAVED_STATE_DIRECTORY,
                        testDirectory.toFile().toString());
        context = TestPlatformContextBuilder.create()
                .withConfiguration(configBuilder.getOrCreateConfig())
                .build();
        signedStateFilePath =
                new SignedStateFilePath(context.getConfiguration().getConfigData(StateCommonConfig.class));
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    /**
     * Make sure the signed state was properly saved.
     */
    private void validateSavingOfState(final SignedState originalState) throws IOException {

        final Path stateDirectory = signedStateFilePath.getSignedStateDirectory(
                MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME, originalState.getRound());

        validateSavingOfState(originalState, stateDirectory);
    }

    /**
     * Make sure the signed state was properly saved.
     */
    private void validateSavingOfState(final SignedState originalState, final Path stateDirectory) throws IOException {
        assertEventuallyEquals(
                -1, originalState::getReservationCount, Duration.ofSeconds(1), "invalid reservation count");

        final Path stateFile = stateDirectory.resolve(MerkleTreeSnapshotReader.SIGNED_STATE_FILE_NAME);
        final Path hashInfoFile = stateDirectory.resolve(SignedStateFileUtils.HASH_INFO_FILE_NAME);
        final Path settingsUsedFile = stateDirectory.resolve("settingsUsed.txt");

        assertTrue(exists(stateFile), "no state file found");
        assertTrue(exists(hashInfoFile), "no hash info file found");
        assertTrue(exists(settingsUsedFile), "no settings used file found");

        assertEquals(-1, originalState.getReservationCount(), "invalid reservation count");

        Configuration configuration =
                TestPlatformContextBuilder.create().build().getConfiguration();
        final DeserializedSignedState deserializedSignedState = readStateFile(
                stateFile,
                TestHederaVirtualMapState::new,
                TEST_PLATFORM_STATE_FACADE,
                PlatformContext.create(configuration));
        SignedState signedState = deserializedSignedState.reservedSignedState().get();
        TestMerkleCryptoFactory.getInstance()
                .digestTreeSync(signedState.getState().getRoot());

        assertNotNull(deserializedSignedState.originalHash(), "hash should not be null");
        assertNotSame(signedState, originalState, "deserialized object should not be the same");

        assertEquals(originalState.getState().getHash(), signedState.getState().getHash(), "hash should match");
        assertEquals(originalState.getState().getHash(), deserializedSignedState.originalHash(), "hash should match");

        signedState.getState().release();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Standard Operation Test")
    void standardOperationTest(final boolean successExpected) throws IOException {
        final SignedState signedState = new RandomSignedStateGenerator().build();
        makeImmutable(signedState);
        hashState((signedState.getState()));

        if (!successExpected) {
            // To make the save fail, create a file with the name of the directory the state will try to be saved to
            final Path savedDir = signedStateFilePath.getSignedStateDirectory(
                    MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME, signedState.getRound());
            Files.createDirectories(savedDir.getParent());
            Files.createFile(savedDir);
        }

        final StateSnapshotManager manager = new DefaultStateSnapshotManager(
                context, MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME, TEST_PLATFORM_STATE_FACADE);

        final StateSavingResult stateSavingResult = manager.saveStateTask(signedState.reserve("test"));

        if (successExpected) {
            assertNotNull(stateSavingResult, "If succeeded, should return a StateSavingResult");
            validateSavingOfState(signedState);
        } else {
            assertNull(stateSavingResult, "If unsuccessful, should return null");
        }
    }

    @Test
    @DisplayName("Save Fatal Signed State")
    void saveFatalSignedState() throws InterruptedException, IOException {
        final SignedState signedState =
                new RandomSignedStateGenerator().setUseBlockingState(true).build();

        final StateSnapshotManager manager = new DefaultStateSnapshotManager(
                context, MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME, TEST_PLATFORM_STATE_FACADE);
        signedState.markAsStateToSave(FATAL_ERROR);
        makeImmutable(signedState);
        hashState((signedState.getState()));
        ((BlockingState) signedState.getState()).enableBlockingSerialization();

        final Thread thread = new ThreadConfiguration(getStaticThreadManager())
                .setInterruptableRunnable(
                        () -> manager.dumpStateTask(StateDumpRequest.create(signedState.reserve("test"))))
                .build(true);

        // State writing should be synchronized. So we shouldn't be able to finish until we unblock.
        MILLISECONDS.sleep(10);
        // shouldn't be finished yet
        assertTrue(thread.isAlive(), "thread should still be blocked");

        ((BlockingState) signedState.getState()).unblockSerialization();
        thread.join(1000);

        final Path stateDirectory = testDirectory.resolve("fatal").resolve("node1234_round" + signedState.getRound());
        // Disabling hash validation as hashes would not be the same in this test because of the migration, explanation:
        // Saving MerkleStateRoot
        // Reading state -> calling `.migrate()` methods -> `MerkleStateRoot.migrate()` returns `VirtualMap`
        // => different hash
        validateSavingOfState(signedState, stateDirectory);
    }

    @Test
    @DisplayName("Save ISS Signed State")
    void saveISSignedState() throws IOException {
        final SignedState signedState = new RandomSignedStateGenerator().build();

        final StateSnapshotManager manager = new DefaultStateSnapshotManager(
                context, MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME, TEST_PLATFORM_STATE_FACADE);
        signedState.markAsStateToSave(ISS);
        makeImmutable(signedState);
        hashState((signedState.getState()));
        manager.dumpStateTask(StateDumpRequest.create(signedState.reserve("test")));

        final Path stateDirectory = testDirectory.resolve("iss").resolve("node1234_round" + signedState.getRound());
        validateSavingOfState(signedState, stateDirectory);
    }

    /**
     * Simulate a sequence of states where a state is saved periodically. Ensure that the proper states are saved, and
     * ensure that states on disk are deleted when they get too old.
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("Sequence Of States Test")
    void sequenceOfStatesTest(final boolean startAtGenesis) throws IOException {

        final Random random = getRandomPrintSeed();

        // Save state every 100 (simulated) seconds
        final int stateSavePeriod = 100;
        final int statesOnDisk = 3;
        final TestConfigBuilder configBuilder = new TestConfigBuilder()
                .withValue(StateConfig_.SAVE_STATE_PERIOD, stateSavePeriod)
                .withValue(StateConfig_.SIGNED_STATE_DISK, statesOnDisk)
                .withValue(
                        StateCommonConfig_.SAVED_STATE_DIRECTORY,
                        testDirectory.toFile().toString());
        final PlatformContext context = TestPlatformContextBuilder.create()
                .withConfiguration(configBuilder.getOrCreateConfig())
                .build();

        // Each state now has a VirtualMap for ROSTERS, and each VirtualMap consumes a lot of RAM.
        // So one cannot keep too many VirtualMaps in memory at once, or OOMs pop up.
        // Therefore, the number of states this test can use at once should be reasonably small:
        final int totalStates = 10;
        final int averageTimeBetweenStates = 10;
        final double standardDeviationTimeBetweenStates = 0.5;

        final StateSnapshotManager manager = new DefaultStateSnapshotManager(
                context, MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME, TEST_PLATFORM_STATE_FACADE);
        final SavedStateController controller = new DefaultSavedStateController(context);

        Instant timestamp;
        final long firstRound;
        Instant nextBoundary;
        final List<SignedState> savedStates = new ArrayList<>();

        if (startAtGenesis) {
            timestamp = Instant.EPOCH;
            firstRound = 1;
            nextBoundary = null;
        } else {
            firstRound = random.nextInt(1000);
            timestamp = Instant.ofEpochSecond(random.nextInt(1000));

            final SignedState initialState = new RandomSignedStateGenerator(random)
                    .setConsensusTimestamp(timestamp)
                    .setRound(firstRound)
                    .build();
            savedStates.add(initialState);
            controller.registerSignedStateFromDisk(initialState);

            nextBoundary = Instant.ofEpochSecond(
                    timestamp.getEpochSecond() / stateSavePeriod * stateSavePeriod + stateSavePeriod);
        }

        for (long round = firstRound; round < totalStates + firstRound; round++) {

            final int secondsDelta = (int)
                    Math.max(1, random.nextGaussian() * standardDeviationTimeBetweenStates + averageTimeBetweenStates);

            timestamp = timestamp.plus(secondsDelta, ChronoUnit.SECONDS);

            final SignedState signedState = new RandomSignedStateGenerator(random)
                    .setConsensusTimestamp(timestamp)
                    .setRound(round)
                    .build();
            final ReservedSignedState reservedSignedState = signedState.reserve("initialTestReservation");

            controller.markSavedState(new StateWithHashComplexity(reservedSignedState, 1));
            makeImmutable(reservedSignedState.get());
            hashState(signedState.getState());

            if (signedState.isStateToSave()) {
                assertTrue(
                        nextBoundary == null || CompareTo.isGreaterThanOrEqualTo(timestamp, nextBoundary),
                        "timestamp should be after the boundary");
                final StateSavingResult stateSavingResult = manager.saveStateTask(reservedSignedState);

                savedStates.add(signedState);

                validateSavingOfState(signedState);

                final List<SavedStateInfo> currentStatesOnDisk = new SignedStateFilePath(
                                context.getConfiguration().getConfigData(StateCommonConfig.class))
                        .getSavedStateFiles(MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME);

                final SavedStateMetadata oldestMetadata =
                        currentStatesOnDisk.getLast().metadata();

                assertNotNull(stateSavingResult, "state should have been saved");
                assertEquals(
                        oldestMetadata.minimumBirthRoundNonAncient(),
                        stateSavingResult.oldestMinimumBirthRoundOnDisk());

                assertTrue(
                        currentStatesOnDisk.size() <= statesOnDisk,
                        "unexpected number of states on disk, current number = " + currentStatesOnDisk.size());

                for (int index = 0; index < currentStatesOnDisk.size(); index++) {

                    final SavedStateInfo savedStateInfo = currentStatesOnDisk.get(index);

                    final SignedState stateFromDisk = assertDoesNotThrow(
                            () -> SignedStateFileReader.readStateFile(
                                            savedStateInfo.stateFile(),
                                            TestHederaVirtualMapState::new,
                                            TEST_PLATFORM_STATE_FACADE,
                                            context)
                                    .reservedSignedState()
                                    .get(),
                            "should be able to read state on disk");

                    final SignedState originalState = savedStates.get(savedStates.size() - index - 1);
                    assertEquals(originalState.getRound(), stateFromDisk.getRound(), "round should match");
                    assertEquals(
                            originalState.getConsensusTimestamp(),
                            stateFromDisk.getConsensusTimestamp(),
                            "timestamp should match");
                    stateFromDisk.getState().release();
                }

                // The first state with a timestamp after this boundary should be saved
                nextBoundary = Instant.ofEpochSecond(
                        timestamp.getEpochSecond() / stateSavePeriod * stateSavePeriod + stateSavePeriod);
            } else {
                assertNotNull(nextBoundary, "if the next boundary is null then the state should have been saved");
                assertTrue(
                        CompareTo.isGreaterThan(nextBoundary, timestamp),
                        "next boundary should be after current timestamp");
            }
        }
    }

    @SuppressWarnings("resource")
    @Test
    @DisplayName("State Deletion Test")
    void stateDeletionTest() throws IOException {
        final Random random = getRandomPrintSeed();
        final int statesOnDisk = 3;

        final TestConfigBuilder configBuilder = new TestConfigBuilder()
                .withValue(StateConfig_.SIGNED_STATE_DISK, statesOnDisk)
                .withValue(
                        StateCommonConfig_.SAVED_STATE_DIRECTORY,
                        testDirectory.toFile().toString());
        final PlatformContext context = TestPlatformContextBuilder.create()
                .withConfiguration(configBuilder.getOrCreateConfig())
                .build();

        final int count = 10;

        final StateSnapshotManager manager = new DefaultStateSnapshotManager(
                context, MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME, TEST_PLATFORM_STATE_FACADE);

        final Path statesDirectory =
                signedStateFilePath.getSignedStatesDirectoryForSwirld(MAIN_CLASS_NAME, SELF_ID, SWIRLD_NAME);

        // Simulate the saving of an ISS state
        final int issRound = 666;
        final Path issDirectory = signedStateFilePath
                .getSignedStatesBaseDirectory()
                .resolve("iss")
                .resolve("node" + SELF_ID + "_round" + issRound);
        final SignedState issState =
                new RandomSignedStateGenerator(random).setRound(issRound).build();
        makeImmutable(issState);
        issState.markAsStateToSave(ISS);
        hashState((issState.getState()));
        manager.dumpStateTask(StateDumpRequest.create(issState.reserve("test")));
        validateSavingOfState(issState, issDirectory);

        // Simulate the saving of a fatal state
        final int fatalRound = 667;
        final Path fatalDirectory = signedStateFilePath
                .getSignedStatesBaseDirectory()
                .resolve("fatal")
                .resolve("node" + SELF_ID + "_round" + fatalRound);
        final SignedState fatalState =
                new RandomSignedStateGenerator(random).setRound(fatalRound).build();
        makeImmutable(fatalState);
        hashState(fatalState.getState());
        fatalState.markAsStateToSave(FATAL_ERROR);
        manager.dumpStateTask(StateDumpRequest.create(fatalState.reserve("test")));
        validateSavingOfState(fatalState, fatalDirectory);

        // Save a bunch of states. After each time, check the states that are still on disk.
        final List<SignedState> states = new ArrayList<>();
        for (int round = 1; round <= count; round++) {
            final SignedState signedState =
                    new RandomSignedStateGenerator(random).setRound(round).build();
            issState.markAsStateToSave(PERIODIC_SNAPSHOT);
            states.add(signedState);
            makeImmutable(signedState);
            hashState(signedState.getState());
            manager.saveStateTask(signedState.reserve("test"));

            // Verify that the states we want to be on disk are still on disk
            for (int i = 1; i <= statesOnDisk; i++) {
                final int roundToValidate = round - i;
                if (roundToValidate < 0) {
                    continue;
                }
                validateSavingOfState(states.get(roundToValidate));
            }

            // Verify that old states are properly deleted
            int filesCount;
            try (Stream<Path> list = Files.list(statesDirectory)) {
                filesCount = (int) list.count();
            }
            assertEquals(
                    Math.min(statesOnDisk, round),
                    filesCount,
                    "unexpected number of states on disk after saving round " + round);

            // ISS/fatal state should still be in place
            validateSavingOfState(issState, issDirectory);
            validateSavingOfState(fatalState, fatalDirectory);
        }
    }

    // FUTURE WORK: https://github.com/hiero-ledger/hiero-consensus-node/issues/19905
    private static void hashState(MerkleNodeState state) {
        TestMerkleCryptoFactory.getInstance().digestTreeSync(state.getRoot());
    }

    private static void makeImmutable(SignedState signedState) {
        signedState.getState().copy().release();
    }
}
