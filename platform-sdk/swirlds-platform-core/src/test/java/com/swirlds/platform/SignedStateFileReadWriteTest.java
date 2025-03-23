// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.common.io.utility.FileUtils.throwIfFileExists;
import static com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader.SIGNED_STATE_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readStateFile;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.CURRENT_ROSTER_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.HASH_INFO_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SIGNATURE_SET_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeHashInfoFile;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeSignatureSetFile;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeSignedStateToDisk;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static java.nio.file.Files.exists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.utility.MerkleTreeVisualizer;
import com.swirlds.common.test.fixtures.RandomUtils;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateFacade;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileUtils;
import com.swirlds.platform.state.snapshot.StateToDiskReason;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.test.fixtures.state.FakeConsensusStateEventHandler;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.state.State;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedState Read/Write Test")
class SignedStateFileReadWriteTest {
    Path testDirectory;

    private static SemanticVersion platformVersion;
    private static PlatformStateFacade stateFacade;

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        final var registry = ConstructableRegistry.getInstance();
        platformVersion =
                SemanticVersion.newBuilder().major(RandomUtils.nextInt(1, 100)).build();
        registry.registerConstructables("com.swirlds.common");
        registry.registerConstructables("org.hiero.consensus.model");
        registry.registerConstructables("com.swirlds.platform");
        registry.registerConstructables("com.swirlds.state");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructables("com.swirlds.merkledb");
        FakeConsensusStateEventHandler.registerMerkleStateRootClassIds();
        stateFacade = new PlatformStateFacade(v -> new BasicSoftwareVersion(platformVersion.major()));
    }

    @BeforeEach
    void beforeEach() throws IOException {
        // Don't use JUnit @TempDir as it runs into a thread race with Merkle DB DataSource release...
        testDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile(
                "SignedStateFileReadWriteTest", FakeConsensusStateEventHandler.CONFIGURATION);
        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(testDirectory.resolve("tmp"));
        MerkleDb.resetDefaultInstancePath();
    }

    @AfterEach
    void tearDown() {
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("writeHashInfoFile() Test")
    void writeHashInfoFileTest() throws IOException {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final SignedState signedState = new RandomSignedStateGenerator()
                .setSoftwareVersion(new BasicSoftwareVersion(platformVersion.minor()))
                .build();
        final MerkleNodeState state = signedState.getState();
        writeHashInfoFile(platformContext, testDirectory, state, stateFacade);
        final StateConfig stateConfig =
                new TestConfigBuilder().getOrCreateConfig().getConfigData(StateConfig.class);

        final Path hashInfoFile = testDirectory.resolve(SignedStateFileUtils.HASH_INFO_FILE_NAME);
        assertTrue(exists(hashInfoFile), "file should exist");

        final String hashInfoString = new MerkleTreeVisualizer(state.getRoot())
                .setDepth(stateConfig.debugHashDepth())
                .render();

        final StringBuilder sb = new StringBuilder();
        try (final BufferedReader br = new BufferedReader(new FileReader(hashInfoFile.toFile()))) {
            br.lines().forEach(line -> sb.append(line).append("\n"));
        }

        final String fileString = sb.toString();
        assertTrue(fileString.contains(hashInfoString), "hash info string not found");
    }

    @Test
    @DisplayName("Write Then Read State File Test")
    void writeThenReadStateFileTest() throws IOException {
        final SignedState signedState = new RandomSignedStateGenerator().build();
        final Path stateFile = testDirectory.resolve(SIGNED_STATE_FILE_NAME);
        final Path signatureSetFile = testDirectory.resolve(SIGNATURE_SET_FILE_NAME);

        assertFalse(exists(stateFile), "signed state file should not yet exist");
        assertFalse(exists(signatureSetFile), "signature set file should not yet exist");

        State state = signedState.getState();
        state.copy();
        state.createSnapshot(testDirectory);
        writeSignatureSetFile(testDirectory, signedState);

        assertTrue(exists(stateFile), "signed state file should be present");
        assertTrue(exists(signatureSetFile), "signature set file should be present");

        MerkleDb.resetDefaultInstancePath();
        Configuration configuration =
                TestPlatformContextBuilder.create().build().getConfiguration();
        final DeserializedSignedState deserializedSignedState =
                readStateFile(stateFile, TEST_PLATFORM_STATE_FACADE, PlatformContext.create(configuration));
        TestMerkleCryptoFactory.getInstance()
                .digestTreeSync(deserializedSignedState
                        .reservedSignedState()
                        .get()
                        .getState()
                        .getRoot());

        assertNotNull(deserializedSignedState.originalHash(), "hash should not be null");
        assertEquals(signedState.getState().getHash(), deserializedSignedState.originalHash(), "hash should match");
        assertEquals(
                signedState.getState().getHash(),
                deserializedSignedState.reservedSignedState().get().getState().getHash(),
                "hash should match");
        assertNotSame(signedState, deserializedSignedState.reservedSignedState(), "state should be a different object");
    }

    @Test
    @DisplayName("writeSavedStateToDisk() Test")
    void writeSavedStateToDiskTest() throws IOException {
        final SignedState signedState = new RandomSignedStateGenerator()
                .setSoftwareVersion(new BasicSoftwareVersion(platformVersion.minor()))
                .build();
        final Path directory = testDirectory.resolve("state");

        final Path stateFile = directory.resolve(SIGNED_STATE_FILE_NAME);
        final Path hashInfoFile = directory.resolve(HASH_INFO_FILE_NAME);
        final Path settingsUsedFile = directory.resolve("settingsUsed.txt");
        final Path addressBookFile = directory.resolve(CURRENT_ROSTER_FILE_NAME);

        throwIfFileExists(stateFile, hashInfoFile, settingsUsedFile, directory);
        final String configDir = testDirectory.resolve("data/saved").toString();
        final Configuration configuration = changeConfigAndConfigHolder(configDir);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        // make immutable
        signedState.getState().copy();

        writeSignedStateToDisk(
                platformContext,
                NodeId.of(0),
                directory,
                signedState,
                StateToDiskReason.PERIODIC_SNAPSHOT,
                stateFacade);

        assertTrue(exists(stateFile), "state file should exist");
        assertTrue(exists(hashInfoFile), "hash info file should exist");
        assertTrue(exists(settingsUsedFile), "settings used file should exist");
        assertTrue(exists(addressBookFile), "address book file should exist");
    }

    private Configuration changeConfigAndConfigHolder(String directory) {
        return new TestConfigBuilder()
                .withValue(StateCommonConfig_.SAVED_STATE_DIRECTORY, directory)
                .getOrCreateConfig();
    }
}
