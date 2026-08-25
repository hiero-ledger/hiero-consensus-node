// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state;

import static org.hiero.consensus.state.SignedStateFileWriter.writeSignedStateToDisk;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.state.StateLifecycleManager;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.VirtualMapStateLifecycleManager;
import com.swirlds.state.test.fixtures.merkle.TestStateUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualDataSource;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.file.FileSystemManager;
import org.hiero.base.utility.test.fixtures.file.TestFileSystemManager;
import org.hiero.consensus.constructable.ConstructableRegistration;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.snapshot.StateToDiskReason;
import org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that writing a freeze state to disk stops background compaction on the data source
 * before creating the snapshot, and that writing a non-freeze state leaves compaction alone.
 */
@DisplayName("SignedStateFileWriter Compaction Tests")
class SignedStateFileWriterTest {

    @TempDir
    Path testDirectory;

    private Configuration configuration;
    private FileSystemManager fileSystemManager;
    private StateLifecycleManager<VirtualMapState, VirtualMap> stateLifecycleManager;

    @BeforeAll
    static void beforeAll() throws ConstructableRegistryException {
        ConstructableRegistration.registerCoreConstructables();
    }

    @BeforeEach
    void beforeEach() {
        configuration = new TestConfigBuilder().getOrCreateConfig();
        fileSystemManager = new TestFileSystemManager(testDirectory);
        stateLifecycleManager = new VirtualMapStateLifecycleManager(
                new NoOpMetrics(), Time.getCurrent(), configuration, fileSystemManager);
    }

    @AfterEach
    void tearDown() {
        TestStateUtils.destroyStateLifecycleManager(stateLifecycleManager);
        RandomSignedStateGenerator.releaseAllBuiltSignedStates();
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @Test
    @DisplayName("Freeze state: compaction is stopped before snapshot")
    void freezeStateStopsCompaction() throws IOException {
        final SignedState signedState =
                new RandomSignedStateGenerator().setFreezeState(true).build();
        signedState.markAsStateToSave(StateToDiskReason.FREEZE_STATE);

        final VirtualDataSource dataSourceSpy = installDataSourceSpy(signedState);

        stateLifecycleManager.initWithState(signedState.getState());
        // The async snapshot path needs the immutable reference released so the copy can be flushed;
        // the sync path (used for freeze) doesn't, but releasing is safe either way.
        stateLifecycleManager.getLatestImmutableState().release();

        writeSignedStateToDisk(
                configuration,
                fileSystemManager,
                NodeId.of(0),
                testDirectory.resolve("freeze-state"),
                StateToDiskReason.FREEZE_STATE,
                signedState.reserve("test"),
                stateLifecycleManager);

        verify(dataSourceSpy).stopAndDisableBackgroundCompaction();

        // Also verify the observable effect: compaction is actually disabled on the real data source
        final MerkleDbDataSource realDs =
                (MerkleDbDataSource) signedState.getState().getRoot().getDataSource();
        assertFalse(realDs.isCompactionEnabled(), "Compaction should be disabled after writing a freeze state");

        stateLifecycleManager.getMutableState().release();
    }

    @Test
    @DisplayName("Non-freeze state: compaction is not stopped")
    void nonFreezeStateDoesNotStopCompaction() throws IOException {
        final SignedState signedState =
                new RandomSignedStateGenerator().setFreezeState(false).build();

        final VirtualDataSource dataSourceSpy = installDataSourceSpy(signedState);

        stateLifecycleManager.initWithState(signedState.getState());
        stateLifecycleManager.getLatestImmutableState().release();

        writeSignedStateToDisk(
                configuration,
                fileSystemManager,
                NodeId.of(0),
                testDirectory.resolve("periodic-state"),
                StateToDiskReason.PERIODIC_SNAPSHOT,
                signedState.reserve("test"),
                stateLifecycleManager);

        verify(dataSourceSpy, never()).stopAndDisableBackgroundCompaction();

        // Compaction should still be enabled
        final MerkleDbDataSource realDs =
                (MerkleDbDataSource) signedState.getState().getRoot().getDataSource();
        assertTrue(realDs.isCompactionEnabled(), "Compaction should remain enabled after writing a non-freeze state");

        stateLifecycleManager.getMutableState().release();
    }

    /**
     * Installs a Mockito spy on the data source of the signed state's VirtualMap so that
     * calls to {@code stopAndDisableBackgroundCompaction()} can be verified.
     *
     * <p>The spy delegates all calls to the real data source, so the full MerkleDb
     * infrastructure remains functional. The spy is installed by reflectively replacing the
     * {@code dataSource} field on the VirtualMap — the same technique used elsewhere in this
     * codebase for cross-package test access to private fields.
     *
     * @return the spy, for use in {@code verify()} assertions
     */
    private static VirtualDataSource installDataSourceSpy(final SignedState signedState) {
        final VirtualMap virtualMap = signedState.getState().getRoot();
        final VirtualDataSource realDataSource = virtualMap.getDataSource();
        final VirtualDataSource dataSourceSpy = spy(realDataSource);
        try {
            final Field dataSourceField = VirtualMap.class.getDeclaredField("dataSource");
            dataSourceField.setAccessible(true);
            dataSourceField.set(virtualMap, dataSourceSpy);
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException("Failed to install data source spy", e);
        }
        return dataSourceSpy;
    }
}
