// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test;

import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_KEY;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_LABEL;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_KEY;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID;
import static com.hedera.node.app.ids.schemas.V0590EntityIdSchema.ENTITY_COUNTS_STATE_LABEL;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_LABEL;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.UPGRADE_DATA_STATE_KEY_PATTERN;
import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mock.Strictness.LENIENT;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.hapi.node.base.ThresholdKey;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.node.app.ids.EntityIdService;
import com.hedera.node.app.ids.ReadableEntityIdStoreImpl;
import com.hedera.node.app.ids.WritableEntityIdStore;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.ReadableFileStoreImpl;
import com.hedera.node.app.service.file.impl.ReadableUpgradeFileStoreImpl;
import com.hedera.node.app.service.file.impl.WritableFileStore;
import com.hedera.node.app.service.file.impl.WritableUpgradeFileStore;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.FilteredReadableStates;
import com.swirlds.state.spi.FilteredWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import com.swirlds.state.test.fixtures.FunctionReadableSingletonState;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.ListReadableQueueState;
import com.swirlds.state.test.fixtures.ListWritableQueueState;
import com.swirlds.state.test.fixtures.MapReadableKVState;
import com.swirlds.state.test.fixtures.MapWritableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class FileTestBase {

    private static final String A_NAME = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String B_NAME = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String C_NAME = "cccccccccccccccccccccccccccccccc";
    private static final Function<String, Key.Builder> KEY_BUILDER =
            value -> Key.newBuilder().ed25519(Bytes.wrap(value.getBytes()));
    public static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();
    public static final Key A_KEY_LIST = Key.newBuilder()
            .keyList(KeyList.newBuilder()
                    .keys(
                            KEY_BUILDER.apply(A_NAME).build(),
                            KEY_BUILDER.apply(B_NAME).build(),
                            KEY_BUILDER.apply(C_NAME).build()))
            .build();
    public static final Key B_KEY_LIST = Key.newBuilder()
            .keyList(KeyList.newBuilder()
                    .keys(
                            KEY_BUILDER.apply(A_NAME).build(),
                            KEY_BUILDER.apply(B_NAME).build(),
                            KEY_BUILDER.apply(C_NAME).build()))
            .build();
    public static final Key A_THRESHOLD_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    KEY_BUILDER.apply(C_NAME).build())
                            .build()))
            .build();
    public static final Key A_COMPLEX_KEY = Key.newBuilder()
            .thresholdKey(ThresholdKey.newBuilder()
                    .threshold(2)
                    .keys(KeyList.newBuilder()
                            .keys(
                                    KEY_BUILDER.apply(A_NAME).build(),
                                    KEY_BUILDER.apply(B_NAME).build(),
                                    A_THRESHOLD_KEY)))
            .build();

    protected static final int UPGRADE_DATA_STATE_ID = 10101;
    protected static final String UPGRADE_DATA_KEY = "FileService_I_UPGRADE_DATA_250";

    protected final Key key = A_COMPLEX_KEY;
    protected final AccountID payerId = AccountID.newBuilder().accountNum(3).build();
    protected final byte[] contents = "contents".getBytes();
    protected final Bytes contentsBytes = Bytes.wrap(contents);

    protected final KeyList keys = A_KEY_LIST.keyList();

    protected final KeyList anotherKeys = B_KEY_LIST.keyList();

    protected final long SHARD =
            DEFAULT_CONFIG.getConfigData(HederaConfig.class).shard();
    protected final long REALM =
            DEFAULT_CONFIG.getConfigData(HederaConfig.class).realm();

    protected final EntityIdFactory idFactory = new FakeEntityIdFactoryImpl(SHARD, REALM);

    protected final FileID WELL_KNOWN_FILE_ID = idFactory.newFileId(1_234L);
    protected final FileID WELL_KNOWN_UPGRADE_FILE_ID = idFactory.newFileId(150L);
    protected final FileID WELL_KNOWN_SYSTEM_FILE_ID = idFactory.newFileId(122L);
    protected final FileID fileId = WELL_KNOWN_FILE_ID;
    protected final FileID fileIdNotExist = FileID.newBuilder().fileNum(6_789L).build();
    protected final FileID fileSystemFileId = WELL_KNOWN_SYSTEM_FILE_ID;
    protected final FileID fileUpgradeFileId = WELL_KNOWN_UPGRADE_FILE_ID;
    protected final com.hederahashgraph.api.proto.java.FileID monoFileID =
            com.hederahashgraph.api.proto.java.FileID.newBuilder()
                    .setFileNum(1_234L)
                    .build();
    protected final Bytes ledgerId = Bytes.wrap(new byte[] {0});
    protected final String memo = "test memo";
    protected final long expirationTime = 1_234_567L;

    protected File file;

    protected File fileSystem;

    protected File upgradeFile;

    protected File fileWithNoKeysAndMemo;

    protected File fileWithNoContent;

    @Mock
    protected ReadableStates readableStates;

    @Mock
    protected WritableStates writableStates;

    @Mock(strictness = LENIENT)
    protected FilteredReadableStates filteredReadableStates;

    @Mock(strictness = LENIENT)
    protected FilteredWritableStates filteredWritableStates;

    @Mock(strictness = LENIENT)
    protected HandleContext handleContext;

    @Mock(strictness = LENIENT)
    protected StoreFactory storeFactory;

    @Mock
    protected ReadableEntityCounters readableEntityCounters;

    @Mock
    protected WritableEntityCounters writableEntityCounters;

    protected MapReadableKVState<FileID, File> readableFileState;
    protected MapWritableKVState<FileID, File> writableFileState;

    protected ListReadableQueueState<ProtoBytes> readableUpgradeStates;
    protected ListWritableQueueState<ProtoBytes> writableUpgradeStates;

    protected MapReadableKVState<FileID, File> readableUpgradeFileStates;
    protected MapWritableKVState<FileID, File> writableUpgradeFileStates;

    protected ReadableFileStoreImpl readableStore;
    protected WritableFileStore writableStore;

    protected ReadableUpgradeFileStoreImpl readableUpgradeFileStore;
    protected WritableUpgradeFileStore writableUpgradeFileStore;

    @BeforeEach
    void commonSetUp() {
        givenValidFile();
        givenValidUpgradeFile(false, true);
        refreshStoresWithCurrentFileOnlyInReadable();
    }

    protected void refreshStoresWithCurrentFileOnlyInReadable() {
        givenEntityCounters(1);
        readableFileState = readableFileState();
        writableFileState = emptyWritableFileState();
        readableUpgradeStates = readableUpgradeDataState();
        writableUpgradeStates = emptyUpgradeDataState();
        readableUpgradeFileStates = readableUpgradeFileState();
        writableUpgradeFileStates = emptyUpgradeFileState();
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(readableFileState);
        given(writableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(writableFileState);
        final var fileStateKey = UPGRADE_DATA_STATE_KEY_PATTERN
                .formatted(fileUpgradeFileId.fileNum())
                .toUpperCase();
        final var fileStateId = StateKey.KeyOneOfType.valueOf(fileStateKey).protoOrdinal();
        given(filteredReadableStates.<ProtoBytes>getQueue(fileStateId)).willReturn(readableUpgradeStates);
        given(filteredWritableStates.<ProtoBytes>getQueue(fileStateId)).willReturn(writableUpgradeStates);
        given(filteredReadableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(readableUpgradeFileStates);
        given(filteredWritableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(writableUpgradeFileStates);
        readableStore = new ReadableFileStoreImpl(readableStates, readableEntityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableFileStore(writableStates, writableEntityCounters);
        readableUpgradeFileStore = new ReadableUpgradeFileStoreImpl(filteredReadableStates);
        writableUpgradeFileStore = new WritableUpgradeFileStore(filteredWritableStates);

        given(handleContext.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);
        given(storeFactory.writableStore(WritableUpgradeFileStore.class)).willReturn(writableUpgradeFileStore);
    }

    protected void givenEntityCounters(int numFiles) {
        given(writableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_ID_STATE_ID,
                        computeLabel(EntityIdService.NAME, ENTITY_ID_KEY),
                        () -> EntityNumber.newBuilder().build(),
                        c -> {}));
        given(writableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionWritableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        computeLabel(EntityIdService.NAME, ENTITY_COUNTS_KEY),
                        () -> EntityCounts.newBuilder().numFiles(numFiles).build(),
                        c -> {}));
        given(readableStates.getSingleton(ENTITY_ID_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_ID_STATE_ID, ENTITY_ID_STATE_LABEL, () -> EntityNumber.newBuilder()
                                .build()));
        given(readableStates.getSingleton(ENTITY_COUNTS_STATE_ID))
                .willReturn(new FunctionReadableSingletonState<>(
                        ENTITY_COUNTS_STATE_ID,
                        ENTITY_COUNTS_STATE_LABEL,
                        () -> EntityCounts.newBuilder().numFiles(numFiles).build()));
        readableEntityCounters = new ReadableEntityIdStoreImpl(readableStates);
        writableEntityCounters = new WritableEntityIdStore(writableStates);
    }

    protected void refreshStoresWithCurrentFileInBothReadableAndWritable() {
        givenEntityCounters(1);
        readableFileState = readableFileState();
        writableFileState = writableFileStateWithOneKey();
        readableUpgradeStates = readableUpgradeDataState();
        writableUpgradeStates = writableUpgradeDataState();
        readableUpgradeFileStates = readableUpgradeFileState();
        writableUpgradeFileStates = writableUpgradeFileState();
        given(readableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(readableFileState);
        given(writableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(writableFileState);
        final var fileStateKey = UPGRADE_DATA_STATE_KEY_PATTERN
                .formatted(fileUpgradeFileId.fileNum())
                .toUpperCase();
        final var fileStateId = StateKey.KeyOneOfType.valueOf(fileStateKey).protoOrdinal();
        given(filteredReadableStates.<ProtoBytes>getQueue(fileStateId)).willReturn(readableUpgradeStates);
        given(filteredWritableStates.<ProtoBytes>getQueue(fileStateId)).willReturn(writableUpgradeStates);
        given(filteredReadableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(readableUpgradeFileStates);
        given(filteredWritableStates.<FileID, File>get(FILES_STATE_ID)).willReturn(writableUpgradeFileStates);
        readableStore = new ReadableFileStoreImpl(readableStates, readableEntityCounters);
        final var configuration = HederaTestConfigBuilder.createConfig();
        writableStore = new WritableFileStore(writableStates, writableEntityCounters);
        readableUpgradeFileStore = new ReadableUpgradeFileStoreImpl(filteredReadableStates);
        writableUpgradeFileStore = new WritableUpgradeFileStore(filteredWritableStates);

        given(storeFactory.writableStore(WritableFileStore.class)).willReturn(writableStore);
        given(storeFactory.writableStore(WritableUpgradeFileStore.class)).willReturn(writableUpgradeFileStore);
    }

    @NonNull
    protected MapWritableKVState<FileID, File> emptyWritableFileState() {
        return MapWritableKVState.<FileID, File>builder(FILES_STATE_ID, FILES_STATE_LABEL)
                .build();
    }

    @NonNull
    protected ListWritableQueueState<ProtoBytes> emptyUpgradeDataState() {
        return ListWritableQueueState.<ProtoBytes>builder(
                        UPGRADE_DATA_STATE_ID, computeLabel(FileService.NAME, UPGRADE_DATA_KEY))
                .build();
    }

    @NonNull
    protected MapWritableKVState<FileID, File> emptyUpgradeFileState() {
        return MapWritableKVState.<FileID, File>builder(FILES_STATE_ID, FILES_STATE_LABEL)
                .build();
    }

    @NonNull
    protected MapWritableKVState<FileID, File> writableFileStateWithOneKey() {
        return MapWritableKVState.<FileID, File>builder(FILES_STATE_ID, FILES_STATE_LABEL)
                .value(fileId, file)
                .value(fileSystemFileId, fileSystem)
                .build();
    }

    @NonNull
    protected MapReadableKVState<FileID, File> readableFileState() {
        return MapReadableKVState.<FileID, File>builder(FILES_STATE_ID, FILES_STATE_LABEL)
                .value(fileId, file)
                .build();
    }

    @NonNull
    protected ListReadableQueueState<ProtoBytes> readableUpgradeDataState() {
        return ListReadableQueueState.<ProtoBytes>builder(
                        UPGRADE_DATA_STATE_ID, computeLabel(FileService.NAME, UPGRADE_DATA_KEY))
                .value(new ProtoBytes(fileSystem.contents()))
                .build();
    }

    @NonNull
    protected ListWritableQueueState<ProtoBytes> writableUpgradeDataState() {
        return ListWritableQueueState.<ProtoBytes>builder(
                        UPGRADE_DATA_STATE_ID, computeLabel(FileService.NAME, UPGRADE_DATA_KEY))
                .value(new ProtoBytes(fileSystem.contents()))
                .build();
    }

    @NonNull
    protected MapReadableKVState<FileID, File> readableUpgradeFileState() {
        return MapReadableKVState.<FileID, File>builder(FILES_STATE_ID, FILES_STATE_LABEL)
                .value(fileUpgradeFileId, upgradeFile)
                .build();
    }

    @NonNull
    protected MapWritableKVState<FileID, File> writableUpgradeFileState() {
        return MapWritableKVState.<FileID, File>builder(FILES_STATE_ID, FILES_STATE_LABEL)
                .value(fileUpgradeFileId, upgradeFile)
                .build();
    }

    protected void givenValidFile() {
        givenValidFile(false);
    }

    protected void givenValidFile(boolean deleted) {
        givenValidFile(deleted, true);
    }

    protected void givenValidFile(boolean deleted, boolean withKeys) {
        file = new File(fileId, expirationTime, withKeys ? keys : null, Bytes.wrap(contents), memo, deleted, 0L);
        fileWithNoKeysAndMemo = new File(fileId, expirationTime, null, Bytes.wrap(contents), null, deleted, 0L);
        fileWithNoContent = new File(fileId, expirationTime, withKeys ? keys : null, null, memo, deleted, 0L);
        fileSystem = new File(
                fileSystemFileId, expirationTime, withKeys ? keys : null, Bytes.wrap(contents), memo, deleted, 0L);
    }

    protected void givenValidUpgradeFile(boolean deleted, boolean withKeys) {
        upgradeFile = new File(
                fileUpgradeFileId, expirationTime, withKeys ? keys : null, Bytes.wrap(contents), memo, deleted, 0L);
    }

    protected File createFile() {
        return new File.Builder()
                .fileId(fileId)
                .expirationSecond(expirationTime)
                .keys(keys)
                .contents(Bytes.wrap(contents))
                .memo(memo)
                .deleted(true)
                .build();
    }

    protected File createUpgradeFile() {
        return new File.Builder()
                .fileId(fileUpgradeFileId)
                .expirationSecond(expirationTime)
                .keys(keys)
                .contents(Bytes.wrap(contents))
                .memo(memo)
                .deleted(true)
                .build();
    }

    protected File createFileEmptyMemoAndKeys() {
        return new File.Builder()
                .fileId(fileId)
                .expirationSecond(expirationTime)
                .contents(Bytes.wrap(contents))
                .deleted(true)
                .build();
    }

    protected File createFileWithoutContent() {
        return new File.Builder()
                .fileId(fileId)
                .expirationSecond(expirationTime)
                .keys(keys)
                .memo(memo)
                .deleted(true)
                .build();
    }
}
