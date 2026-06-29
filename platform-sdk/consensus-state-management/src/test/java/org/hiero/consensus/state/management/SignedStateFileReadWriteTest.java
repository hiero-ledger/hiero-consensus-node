// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.management;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.randomUtf8Bytes;
import static com.swirlds.platform.test.fixtures.state.TestStateUtils.destroyStateLifecycleManager;
import static java.nio.file.Files.exists;
import static org.hiero.base.file.FileUtils.throwIfFileExists;
import static org.hiero.consensus.state.management.SignedStateFileReader.readState;
import static org.hiero.consensus.state.management.SignedStateFileWriter.writeHashInfoFile;
import static org.hiero.consensus.state.management.SignedStateFileWriter.writeSignatureSetFile;
import static org.hiero.consensus.state.management.SignedStateFileWriter.writeSignedStateToDisk;
import static org.hiero.consensus.state.management.StateFileManagerTests.hashState;
import static org.hiero.consensus.state.management.persistence.SignedStateFileUtils.CONSENSUS_SNAPSHOT_FILE_NAME;
import static org.hiero.consensus.state.management.persistence.SignedStateFileUtils.CURRENT_ROSTER_FILE_NAME;
import static org.hiero.consensus.state.management.persistence.SignedStateFileUtils.HASH_INFO_FILE_NAME;
import static org.hiero.consensus.state.management.persistence.SignedStateFileUtils.SIGNATURE_SET_FILE_NAME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Mnemonics;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.file.FileUtils;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.base.utility.test.fixtures.file.TestFileSystemManager;
import org.hiero.consensus.config.PathsConfig_;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.state.management.persistence.SignedStateFileUtils;
import org.hiero.consensus.state.saved.DeserializedSignedState;
import org.hiero.consensus.state.signed.SigSet;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.snapshot.StateToDiskReason;
import org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SignedState Read/Write Test")
class SignedStateFileReadWriteTest {

    @TempDir
    Path testDirectory;

    private static SemanticVersion platformVersion;
    private StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        platformVersion =
                SemanticVersion.newBuilder().major(RandomUtils.nextInt(1, 100)).build();
        ConstructableRegistration.registerCoreConstructables();
    }

    @BeforeEach
    void beforeEach() throws IOException {
        testDirectory = testDirectory.resolve("SignedStateFileReadWriteTest");
        if (Files.exists(testDirectory)) {
            FileUtils.delete(testDirectory);
        }
        Files.createDirectories(testDirectory);
        final Time time = Time.getCurrent();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final FileSystemManager fileSystemManager = new TestFileSystemManager(testDirectory);
        stateLifecycleManager =
                new VirtualMapStateLifecycleManager(new NoOpMetrics(), time, configuration, fileSystemManager);
    }

    @AfterEach
    void tearDown() {
        destroyStateLifecycleManager(stateLifecycleManager);
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("writeHashInfoFile() Test")
    void writeHashInfoFileTest() throws IOException {
        final SignedState signedState = new RandomSignedStateGenerator()
                .setSoftwareVersion(platformVersion)
                .build();
        final VirtualMapState state = signedState.getState();
        writeHashInfoFile(testDirectory, state);

        final Path hashInfoFile = testDirectory.resolve(SignedStateFileUtils.HASH_INFO_FILE_NAME);
        assertTrue(exists(hashInfoFile), "file should exist");

        final String mnemonicString = Mnemonics.generateMnemonic(state.getHash());

        final StringBuilder sb = new StringBuilder();
        try (final BufferedReader br = new BufferedReader(new FileReader(hashInfoFile.toFile()))) {
            br.lines().forEach(line -> sb.append(line).append("\n"));
        }

        final String fileString = sb.toString();
        assertTrue(fileString.contains(mnemonicString), "hash info string not found");
        state.release();
    }

    @Test
    @DisplayName("Write Then Read State File Test")
    void writeThenReadStateFileTest() throws IOException, ParseException {
        final SignedState signedState = new RandomSignedStateGenerator().build();
        final SigSet sigSet = new SigSet();
        sigSet.addSignature(NodeId.of(1), new Signature(SignatureType.ED25519, randomUtf8Bytes(16)));
        signedState.setSigSet(sigSet);
        final Path signatureSetFile = testDirectory.resolve(SIGNATURE_SET_FILE_NAME);

        assertFalse(exists(signatureSetFile), "signature set file should not yet exist");

        VirtualMapState state = signedState.getState();
        stateLifecycleManager.initWithState(state);
        stateLifecycleManager.getMutableState().release();
        hashState(signedState);
        stateLifecycleManager.createSnapshot(signedState.getState(), testDirectory);
        writeSignatureSetFile(testDirectory, signedState);

        assertTrue(exists(signatureSetFile), "signature set file should be present");

        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final DeserializedSignedState deserializedSignedState =
                readState(testDirectory, configuration, stateLifecycleManager);
        hashState(deserializedSignedState.reservedSignedState().get());

        final VirtualMapMetadata originalMetadata =
                signedState.getState().getRoot().getMetadata();
        final VirtualMapMetadata loadedMetadata = deserializedSignedState
                .reservedSignedState()
                .get()
                .getState()
                .getRoot()
                .getMetadata();

        assertEquals(originalMetadata, loadedMetadata, "metadata should be equal");

        assertNotNull(deserializedSignedState.originalHash(), "hash should not be null");
        assertEquals(signedState.getState().getHash(), deserializedSignedState.originalHash(), "hash should match");
        assertEquals(
                signedState.getState().getHash(),
                deserializedSignedState.reservedSignedState().get().getState().getHash(),
                "hash should match");
        assertNotSame(
                signedState, deserializedSignedState.reservedSignedState().get(), "state should be a different object");
        state.release();
        deserializedSignedState.reservedSignedState().get().getState().release();
    }

    @Test
    @DisplayName("writeSavedStateToDisk() Test")
    void writeSavedStateToDiskTest() throws IOException {
        final SignedState signedState = new RandomSignedStateGenerator()
                .setSoftwareVersion(platformVersion)
                .build();
        final Path directory = testDirectory.resolve("state");
        stateLifecycleManager.initWithState(signedState.getState());

        final Path hashInfoFile = directory.resolve(HASH_INFO_FILE_NAME);
        final Path settingsUsedFile = directory.resolve("settingsUsed.txt");
        final Path addressBookFile = directory.resolve(CURRENT_ROSTER_FILE_NAME);
        final Path consensusSnapshotFile = directory.resolve(CONSENSUS_SNAPSHOT_FILE_NAME);

        throwIfFileExists(hashInfoFile, settingsUsedFile, directory);
        final String configDir = testDirectory.resolve("data/saved").toString();
        final Configuration configuration = changeConfigAndConfigHolder(configDir);

        // Async snapshot requires all references to the state being written to disk to be released
        stateLifecycleManager.getLatestImmutableState().release();

        writeSignedStateToDisk(
                configuration,
                new FileSystemManager(),
                NodeId.of(0),
                directory,
                StateToDiskReason.PERIODIC_SNAPSHOT,
                signedState.reserve("test"),
                stateLifecycleManager);

        assertTrue(exists(hashInfoFile), "hash info file should exist");
        assertTrue(exists(settingsUsedFile), "settings used file should exist");
        assertTrue(exists(addressBookFile), "address book file should exist");
        assertTrue(exists(consensusSnapshotFile), "consensus snapshot file should exist");

        stateLifecycleManager.getMutableState().release();
    }

    private Configuration changeConfigAndConfigHolder(String directory) {
        return new TestConfigBuilder()
                .withValue(PathsConfig_.SAVED_STATE_DIR, directory)
                .getOrCreateConfig();
    }
}
