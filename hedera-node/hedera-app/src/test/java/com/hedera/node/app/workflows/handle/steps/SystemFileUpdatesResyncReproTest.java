// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.ServicesConfigurationList;
import com.hedera.hapi.node.base.Setting;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.file.FileUpdateTransactionBody;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.blocks.impl.streaming.BlockNodeConnectionManager;
import com.hedera.node.app.config.ConfigProviderImpl;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeManager;
import com.hedera.node.app.fixtures.state.FakeState;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.spi.fixtures.ids.FakeEntityIdFactoryImpl;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.converter.BytesConverter;
import com.hedera.node.config.converter.LongPairConverter;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.types.BlockStreamWriterMode;
import com.hedera.node.config.types.LongPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.state.spi.CommittableWritableStates;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Reproductions of recovery gaps in {@link SystemFileUpdates} for the network properties file (0.0.121): recovery
 * reads the committed configuration through the in-memory configuration that the rolled-back update produced, and
 * its block node streaming transition is not ordered with the one the rolled-back update queued.
 */
@ExtendWith(MockitoExtension.class)
class SystemFileUpdatesResyncReproTest {
    private static final long REMAPPED_PROPERTIES_FILE_NUM = 150L;
    private static final long REMAPPED_PERMISSIONS_FILE_NUM = 151L;

    @Mock(strictness = Strictness.LENIENT)
    private ConfigProviderImpl configProvider;

    @Mock
    private ExchangeRateManager exchangeRateManager;

    @Mock
    private FeeManager feeManager;

    @Mock
    private ThrottleServiceManager throttleServiceManager;

    @Mock(strictness = Strictness.LENIENT)
    private BlockNodeConnectionManager blockNodeConnectionManager;

    private final AtomicReference<Configuration> activeConfig = new AtomicReference<>();
    private FakeState state;
    private FakeEntityIdFactoryImpl idFactory;
    private SystemFileUpdates subject;

    @BeforeEach
    void setUp() {
        state = new FakeState().addService(FileService.NAME, Map.of(FILES_STATE_ID, new HashMap<FileID, File>()));
        activeConfig.set(configWith(Map.of()));
        final var hederaConfig = activeConfig.get().getConfigData(HederaConfig.class);
        idFactory = new FakeEntityIdFactoryImpl(hederaConfig.shard(), hederaConfig.realm());
        when(configProvider.getConfiguration()).thenAnswer(inv -> new VersionedConfigImpl(activeConfig.get(), 1L));
        // Like the real provider, the active configuration becomes the one built from the given properties
        doAnswer(inv -> {
                    activeConfig.set(configWith(settingsIn(inv.getArgument(0))));
                    return null;
                })
                .when(configProvider)
                .update(any(), any());
        subject = new SystemFileUpdates(
                configProvider, exchangeRateManager, feeManager, throttleServiceManager, blockNodeConnectionManager);
    }

    /**
     * A rolled-back 0.0.121 update that remapped {@code files.networkProperties} leaves that mapping in the in-memory
     * configuration. Recovery then reads the network properties from the remapped file instead of the committed
     * 0.0.121, so the committed properties are not restored.
     */
    @Test
    void networkPropertiesRecoveryReadsTheCommittedFileDespiteARemappedFileNumber() {
        final var filesConfig = activeConfig.get().getConfigData(FilesConfig.class);
        final var propertiesId = idFactory.newFileId(filesConfig.networkProperties());
        final var permissionsId = idFactory.newFileId(filesConfig.hapiPermissions());
        final var committedProperties = configListOf(Map.of("tokens.maxPerAccount", "1000"));
        final var permissions = Bytes.wrap("committed permissions");
        commitFile(permissionsId, permissions);

        // The update remaps the network properties file, and is applied in memory
        final var remappingProperties =
                configListOf(Map.of("files.networkProperties", Long.toString(REMAPPED_PROPERTIES_FILE_NUM)));
        commitFile(propertiesId, remappingProperties);
        final var target =
                subject.resyncTarget(state, fileUpdateOf(propertiesId)).orElseThrow();
        subject.handleTxBody(state, fileUpdateOf(propertiesId));
        assertThat(activeConfig.get().getConfigData(FilesConfig.class).networkProperties())
                .isEqualTo(REMAPPED_PROPERTIES_FILE_NUM);

        // The update is rolled back, and memory is re-derived from the committed file
        commitFile(propertiesId, committedProperties);
        subject.resyncIfChanged(state, target);

        verify(configProvider).update(committedProperties, permissions);
        assertThat(activeConfig.get().getConfigData(FilesConfig.class).networkProperties())
                .isEqualTo(filesConfig.networkProperties());
    }

    /**
     * A rolled-back 0.0.121 update that remapped {@code files.hapiPermissions} leaves that mapping in the in-memory
     * configuration. Recovery of the network properties also re-reads the sibling permissions file; it must read the
     * committed permissions from the pre-update permissions file, not from the file the rolled-back update remapped to.
     */
    @Test
    void networkPropertiesRecoveryReadsTheCommittedSiblingPermissionsDespiteARemappedPermissionsFile() {
        final var filesConfig = activeConfig.get().getConfigData(FilesConfig.class);
        final var propertiesId = idFactory.newFileId(filesConfig.networkProperties());
        final var permissionsId = idFactory.newFileId(filesConfig.hapiPermissions());
        final var remappedPermissionsId = idFactory.newFileId(REMAPPED_PERMISSIONS_FILE_NUM);
        final var committedProperties = configListOf(Map.of("tokens.maxPerAccount", "1000"));
        final var committedPermissions = Bytes.wrap("committed permissions");
        // The committed permissions live at the default permissions file; a different file sits where the update
        // remaps permissions to, so reading the wrong file would be observable
        commitFile(permissionsId, committedPermissions);
        commitFile(remappedPermissionsId, Bytes.wrap("remapped permissions"));

        // The update remaps the permissions file number, and is applied in memory
        final var remappingProperties =
                configListOf(Map.of("files.hapiPermissions", Long.toString(REMAPPED_PERMISSIONS_FILE_NUM)));
        commitFile(propertiesId, remappingProperties);
        final var target =
                subject.resyncTarget(state, fileUpdateOf(propertiesId)).orElseThrow();
        subject.handleTxBody(state, fileUpdateOf(propertiesId));
        assertThat(activeConfig.get().getConfigData(FilesConfig.class).hapiPermissions())
                .isEqualTo(REMAPPED_PERMISSIONS_FILE_NUM);

        // The update is rolled back, and memory is re-derived from the committed files
        commitFile(propertiesId, committedProperties);
        subject.resyncIfChanged(state, target);

        // Recovery must push the committed permissions read from the pre-update permissions file, not the remapped one
        verify(configProvider).update(committedProperties, committedPermissions);
    }

    /**
     * A rolled-back 0.0.121 update that turned gRPC block streaming off queues a shutdown of the block node
     * connections; recovery then queues a start. Both run asynchronously with no ordering between them, so if the
     * start runs first, streaming ends up off although the committed writer mode streams over gRPC.
     */
    @Test
    void blockNodeStreamingFollowsTheCommittedWriterModeWhateverOrderTheQueuedTransitionsRunIn() {
        activeConfig.set(configWith(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC.name())));
        final var filesConfig = activeConfig.get().getConfigData(FilesConfig.class);
        final var propertiesId = idFactory.newFileId(filesConfig.networkProperties());
        commitFile(idFactory.newFileId(filesConfig.hapiPermissions()), Bytes.wrap("permissions"));
        final var committedProperties =
                configListOf(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC.name()));
        final var fileOnlyProperties =
                configListOf(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE.name()));
        final List<String> transitions = new ArrayList<>();
        doAnswer(inv -> transitions.add("shutdown"))
                .when(blockNodeConnectionManager)
                .shutdown();
        doAnswer(inv -> transitions.add("start"))
                .when(blockNodeConnectionManager)
                .start();

        final List<Runnable> queued = new ArrayList<>();
        try (final MockedStatic<CompletableFuture> mocked = Mockito.mockStatic(CompletableFuture.class)) {
            mocked.when(() -> CompletableFuture.runAsync(any(Runnable.class))).thenAnswer(inv -> {
                queued.add(inv.getArgument(0));
                return new CompletableFuture<>();
            });

            commitFile(propertiesId, fileOnlyProperties);
            final var target =
                    subject.resyncTarget(state, fileUpdateOf(propertiesId)).orElseThrow();
            subject.handleTxBody(state, fileUpdateOf(propertiesId));

            commitFile(propertiesId, committedProperties);
            subject.resyncIfChanged(state, target);
        }
        // The rolled-back update queued one transition and recovery queued the other; without both the test cannot
        // show the ordering gap, so require two queued tasks before replaying them reversed
        assertThat(queued).hasSize(2);

        // The common pool may run a later task first
        for (int k = queued.size() - 1; k >= 0; k--) {
            queued.get(k).run();
        }
        // Each task reconciles to the committed writer mode when it runs, so both resolve to the committed
        // FILE_AND_GRPC (a start) regardless of the order they run in
        assertThat(transitions).hasSize(2).containsOnly("start");

        // Streaming was on before the rolled-back update, and the committed writer mode keeps it on
        boolean streaming = true;
        for (final var transition : transitions) {
            streaming = transition.equals("start");
        }
        assertThat(streaming)
                .as("block node streaming must end on, matching the committed FILE_AND_GRPC writer mode")
                .isTrue();
    }

    /** Writes the file through committed writable states, so later reads see it as the state's readable view does. */
    private void commitFile(final FileID fileID, final Bytes contents) {
        final var writableStates = state.getWritableStates(FileService.NAME);
        writableStates
                .<FileID, File>get(FILES_STATE_ID)
                .put(fileID, File.newBuilder().fileId(fileID).contents(contents).build());
        ((CommittableWritableStates) writableStates).commit();
    }

    private TransactionBody fileUpdateOf(final FileID fileID) {
        return TransactionBody.newBuilder()
                .transactionID(TransactionID.newBuilder()
                        .accountID(idFactory.newAccountId(50L))
                        .build())
                .fileUpdate(FileUpdateTransactionBody.newBuilder().fileID(fileID))
                .build();
    }

    private static Bytes configListOf(final Map<String, String> settings) {
        return ServicesConfigurationList.PROTOBUF.toBytes(ServicesConfigurationList.newBuilder()
                .nameValue(settings.entrySet().stream()
                        .map(e -> Setting.newBuilder()
                                .name(e.getKey())
                                .value(e.getValue())
                                .build())
                        .toList())
                .build());
    }

    private static Map<String, String> settingsIn(final Bytes properties) {
        try {
            final Map<String, String> settings = new HashMap<>();
            ServicesConfigurationList.PROTOBUF
                    .parseStrict(properties.toReadableSequentialData())
                    .nameValue()
                    .forEach(s -> settings.put(s.name(), s.value()));
            return settings;
        } catch (final Exception e) {
            // Like ConfigProviderImpl, an unparseable source contributes no settings
            return Map.of();
        }
    }

    private static Configuration configWith(final Map<String, String> settings) {
        final var builder = new TestConfigBuilder(false)
                .withConverter(Bytes.class, new BytesConverter())
                .withConverter(LongPair.class, new LongPairConverter())
                .withConfigDataType(FilesConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(LedgerConfig.class)
                .withConfigDataType(BlockStreamConfig.class);
        settings.forEach(builder::withValue);
        return builder.getOrCreateConfig();
    }
}
