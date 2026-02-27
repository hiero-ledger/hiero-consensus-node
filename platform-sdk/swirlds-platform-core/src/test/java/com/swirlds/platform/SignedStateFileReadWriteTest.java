// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform;

import static com.swirlds.base.test.fixtures.util.DataUtils.randomUtf8Bytes;
import static com.swirlds.common.io.utility.FileUtils.throwIfFileExists;
import static com.swirlds.platform.StateFileManagerTests.hashState;
import static com.swirlds.platform.state.snapshot.SignedStateFileReader.readState;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.CURRENT_ROSTER_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.HASH_INFO_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileUtils.SIGNATURE_SET_FILE_NAME;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeHashInfoFile;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeSignatureSetFile;
import static com.swirlds.platform.state.snapshot.SignedStateFileWriter.writeSignedStateToDisk;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static com.swirlds.platform.test.fixtures.state.TestStateUtils.destroyStateLifecycleManager;
import static java.nio.file.Files.exists;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.pbj.runtime.ParseException;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.constructable.ConstructableRegistration;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.utility.Mnemonics;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.platform.state.snapshot.SignedStateFileUtils;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.internal.merkle.VirtualMapMetadata;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.state.signed.SigSet;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.snapshot.StateToDiskReason;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SignedState Read/Write Test")
class SignedStateFileReadWriteTest {
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
        testDirectory = LegacyTemporaryFileBuilder.buildTemporaryFile("SignedStateFileReadWriteTest", CONFIGURATION);
        stateLifecycleManager = new VirtualMapStateLifecycleManager(new NoOpMetrics(), new FakeTime(), CONFIGURATION);
        LegacyTemporaryFileBuilder.overrideTemporaryFileLocation(testDirectory.resolve("tmp"));
    }

    @AfterEach
    void tearDown() {
        destroyStateLifecycleManager(stateLifecycleManager);
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
    }

    @Test
    @DisplayName("writeHashInfoFile() Test")
    void writeHashInfoFileTest() throws IOException {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final SignedState signedState = new RandomSignedStateGenerator()
                .setSoftwareVersion(platformVersion)
                .build();
        final VirtualMapState state = signedState.getState();
        writeHashInfoFile(platformContext, testDirectory, state);

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

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final DeserializedSignedState deserializedSignedState =
                readState(testDirectory, platformContext, stateLifecycleManager);
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

        throwIfFileExists(hashInfoFile, settingsUsedFile, directory);
        final String configDir = testDirectory.resolve("data/saved").toString();
        final Configuration configuration = changeConfigAndConfigHolder(configDir);

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        stateLifecycleManager.getMutableState().release();
        hashState(signedState);

        writeSignedStateToDisk(
                platformContext,
                NodeId.of(0),
                directory,
                StateToDiskReason.PERIODIC_SNAPSHOT,
                signedState,
                stateLifecycleManager);

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
