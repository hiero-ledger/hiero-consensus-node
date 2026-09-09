// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test;

import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_KEY;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_KEY;
import static com.hedera.node.app.service.entityid.impl.schemas.V0730EntityIdSchema.HIGHEST_NODE_ID_STATE_ID;
import static com.hedera.node.app.service.file.impl.RetiredFeeScheduleFileMigration.RETIRED_FEE_SCHEDULE_FILE_NUM;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_LABEL;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.file.impl.RetiredFeeScheduleFileMigration;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Verifies the one-shot removal of the retired legacy fee schedule file {@code 0.0.111}.
 *
 * <p>Shard and realm are deliberately non-zero so that an implementation which hardcoded them to
 * zero would fail to find the file and silently pass.
 */
class RetiredFeeScheduleFileMigrationTest {

    private static final long SHARD = 3L;
    private static final long REALM = 7L;

    private static final Configuration CONFIG = HederaTestConfigBuilder.create()
            .withValue("hedera.shard", SHARD)
            .withValue("hedera.realm", REALM)
            .getOrCreateConfig();

    private static final FileID RETIRED_FEE_SCHEDULE_FILE_ID = fileId(RETIRED_FEE_SCHEDULE_FILE_NUM);
    private static final FileID SIMPLE_FEE_SCHEDULE_FILE_ID = fileId(113L);

    private final AtomicReference<EntityCounts> counts = new AtomicReference<>();

    @Test
    void removesRetiredFileAndDecrementsFileCount() {
        final var states = statesWith(2, RETIRED_FEE_SCHEDULE_FILE_ID, SIMPLE_FEE_SCHEDULE_FILE_ID);
        final var fileStore = storeOver(states);

        RetiredFeeScheduleFileMigration.removeIfPresent(fileStore, CONFIG);
        states.commit();

        assertTrue(fileStore.get(RETIRED_FEE_SCHEDULE_FILE_ID).isEmpty());
        assertTrue(fileStore.get(SIMPLE_FEE_SCHEDULE_FILE_ID).isPresent(), "unrelated files must survive");
        assertEquals(1, counts.get().numFiles());
    }

    @Test
    void isNoOpWhenRetiredFileWasNeverCreated() {
        final var states = statesWith(1, SIMPLE_FEE_SCHEDULE_FILE_ID);
        final var fileStore = storeOver(states);

        RetiredFeeScheduleFileMigration.removeIfPresent(fileStore, CONFIG);
        states.commit();

        assertTrue(fileStore.get(SIMPLE_FEE_SCHEDULE_FILE_ID).isPresent());
        assertEquals(1, counts.get().numFiles(), "file count must not move when nothing was removed");
    }

    @Test
    void ignoresRetiredFileNumberInAnotherShardOrRealm() {
        final var otherShard = FileID.newBuilder()
                .shardNum(SHARD + 1)
                .realmNum(REALM)
                .fileNum(RETIRED_FEE_SCHEDULE_FILE_NUM)
                .build();
        final var states = statesWith(1, otherShard);
        final var fileStore = storeOver(states);

        RetiredFeeScheduleFileMigration.removeIfPresent(fileStore, CONFIG);
        states.commit();

        assertTrue(fileStore.get(otherShard).isPresent());
        assertEquals(1, counts.get().numFiles());
    }

    /**
     * In production the node-removal block decrements {@code numNodes} and commits through the same
     * entity id store before this migration runs, so the file decrement must read through the earlier
     * commit rather than a stale snapshot.
     */
    @Test
    void decrementSurvivesAnEarlierCommitOnTheSameCounter() {
        counts.set(EntityCounts.newBuilder().numFiles(2).numNodes(4).build());
        final var states = statesOver(RETIRED_FEE_SCHEDULE_FILE_ID, SIMPLE_FEE_SCHEDULE_FILE_ID);
        final var entityIdStore = new WritableEntityIdStoreImpl(states);

        entityIdStore.decrementEntityTypeCounter(EntityType.NODE);
        states.commit();

        RetiredFeeScheduleFileMigration.removeIfPresent(new WritableFileStore(states, entityIdStore), CONFIG);
        states.commit();

        assertEquals(1, counts.get().numFiles());
        assertEquals(3, counts.get().numNodes(), "the earlier node decrement must not be reverted");
    }

    /**
     * The overload {@code HandleWorkflow} actually calls: it builds the file store from the writable
     * file service states so the workflow itself stays a single call.
     */
    @Test
    void statesOverloadRemovesTheRetiredFile() {
        final var states = statesWith(2, RETIRED_FEE_SCHEDULE_FILE_ID, SIMPLE_FEE_SCHEDULE_FILE_ID);
        final var entityIdStore = new WritableEntityIdStoreImpl(states);

        RetiredFeeScheduleFileMigration.removeIfPresent(states, entityIdStore, CONFIG);
        states.commit();

        final var fileStore = new WritableFileStore(states, entityIdStore);
        assertTrue(fileStore.get(RETIRED_FEE_SCHEDULE_FILE_ID).isEmpty());
        assertTrue(fileStore.get(SIMPLE_FEE_SCHEDULE_FILE_ID).isPresent());
        assertEquals(1, counts.get().numFiles());
    }

    @Test
    void statesOverloadThrowsOnNullArguments() {
        final var states = statesWith(1, SIMPLE_FEE_SCHEDULE_FILE_ID);
        final var entityIdStore = new WritableEntityIdStoreImpl(states);
        assertThrows(
                NullPointerException.class,
                () -> RetiredFeeScheduleFileMigration.removeIfPresent(null, entityIdStore, CONFIG));
        assertThrows(
                NullPointerException.class,
                () -> RetiredFeeScheduleFileMigration.removeIfPresent(states, null, CONFIG));
    }

    @Test
    void throwsOnNullArguments() {
        final var fileStore = storeOver(statesWith(0));
        assertThrows(NullPointerException.class, () -> RetiredFeeScheduleFileMigration.removeIfPresent(null, CONFIG));
        assertThrows(
                NullPointerException.class, () -> RetiredFeeScheduleFileMigration.removeIfPresent(fileStore, null));
    }

    @Test
    void retiredFileNumberIsOneEleven() {
        assertEquals(111L, RETIRED_FEE_SCHEDULE_FILE_NUM);
    }

    @Test
    void isUninstantiable() throws NoSuchMethodException {
        final var constructor = RetiredFeeScheduleFileMigration.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    private static FileID fileId(final long fileNum) {
        return FileID.newBuilder()
                .shardNum(SHARD)
                .realmNum(REALM)
                .fileNum(fileNum)
                .build();
    }

    private WritableFileStore storeOver(final MapWritableStates states) {
        return new WritableFileStore(states, new WritableEntityIdStoreImpl(states));
    }

    private MapWritableStates statesWith(final int numFiles, final FileID... fileIds) {
        counts.set(EntityCounts.newBuilder().numFiles(numFiles).build());
        return statesOver(fileIds);
    }

    private MapWritableStates statesOver(final FileID... fileIds) {
        final var filesBuilder = MapWritableKVState.<FileID, File>builder(FILES_STATE_ID, FILES_STATE_LABEL);
        for (final var fileId : fileIds) {
            filesBuilder.value(fileId, File.newBuilder().fileId(fileId).build());
        }
        return MapWritableStates.builder()
                .state(filesBuilder.build())
                .state(new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        computeLabel(EntityIdService.NAME, ENTITY_COUNTS_KEY),
                        counts::get,
                        counts::set))
                .state(new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID,
                        computeLabel(EntityIdService.NAME, ENTITY_ID_KEY),
                        () -> EntityNumber.DEFAULT,
                        n -> {}))
                .state(new FunctionWritableSingletonState<>(
                        HIGHEST_NODE_ID_STATE_ID,
                        computeLabel(EntityIdService.NAME, HIGHEST_NODE_ID_KEY),
                        () -> NodeId.DEFAULT,
                        n -> {}))
                .build();
    }
}
