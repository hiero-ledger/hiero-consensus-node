// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.steps;

import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.FILES_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.JumboTransactionsConfig;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
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
     * A 0.0.121 update that switches the writer mode from FILE_AND_GRPC to FILE proposes a shutdown of block node
     * streaming, but the proposed mode is tentative until the transaction commits. If the update rolls back, the
     * committed mode is still FILE_AND_GRPC: no transition may be queued at all, in particular not a shutdown that a
     * recovery-time restart then has to undo.
     */
    @Test
    void rolledBackWriterModeChangeQueuesNoStreamingTransition() {
        activeConfig.set(configWith(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC.name())));
        final var filesConfig = activeConfig.get().getConfigData(FilesConfig.class);
        final var propertiesId = idFactory.newFileId(filesConfig.networkProperties());
        commitFile(idFactory.newFileId(filesConfig.hapiPermissions()), Bytes.wrap("permissions"));
        final var committedProperties =
                configListOf(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC.name()));
        final var fileOnlyProperties =
                configListOf(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE.name()));
        final List<Runnable> queued = new ArrayList<>();
        final var queuingSubject = new SystemFileUpdates(
                configProvider,
                exchangeRateManager,
                feeManager,
                throttleServiceManager,
                blockNodeConnectionManager,
                queued::add);

        // The update applies in memory: the configuration now tentatively proposes FILE
        commitFile(propertiesId, fileOnlyProperties);
        final var target =
                queuingSubject.resyncTarget(state, fileUpdateOf(propertiesId)).orElseThrow();
        queuingSubject.handleTxBody(state, fileUpdateOf(propertiesId));
        assertThat(activeConfig.get().getConfigData(BlockStreamConfig.class).writerMode())
                .isEqualTo(BlockStreamWriterMode.FILE);
        assertThat(queued)
                .as("no transition is queued before the transaction's outcome is known")
                .isEmpty();

        // The update rolls back; recovery re-derives the committed FILE_AND_GRPC mode and then reconciles streaming
        commitFile(propertiesId, committedProperties);
        queuingSubject.resyncIfChanged(state, target.asRolledBack());
        queuingSubject.reconcileBlockNodeStreaming(target);

        assertThat(activeConfig.get().getConfigData(BlockStreamConfig.class).writerMode())
                .isEqualTo(BlockStreamWriterMode.FILE_AND_GRPC);
        assertThat(queued)
                .as("the committed mode equals the prior mode, so nothing is queued")
                .isEmpty();
        verify(blockNodeConnectionManager, never()).shutdown();
        verify(blockNodeConnectionManager, never()).start();
    }

    /**
     * A committed 0.0.121 update that switches the writer mode queues exactly one transition, carrying the committed
     * mode, after the transaction commits.
     */
    @Test
    void committedWriterModeChangeQueuesOneTransitionWithTheCommittedMode() {
        activeConfig.set(configWith(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC.name())));
        final var filesConfig = activeConfig.get().getConfigData(FilesConfig.class);
        final var propertiesId = idFactory.newFileId(filesConfig.networkProperties());
        commitFile(idFactory.newFileId(filesConfig.hapiPermissions()), Bytes.wrap("permissions"));
        final var fileOnlyProperties =
                configListOf(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE.name()));
        final List<Runnable> queued = new ArrayList<>();
        final var queuingSubject = new SystemFileUpdates(
                configProvider,
                exchangeRateManager,
                feeManager,
                throttleServiceManager,
                blockNodeConnectionManager,
                queued::add);

        commitFile(propertiesId, fileOnlyProperties);
        final var target =
                queuingSubject.resyncTarget(state, fileUpdateOf(propertiesId)).orElseThrow();
        queuingSubject.handleTxBody(state, fileUpdateOf(propertiesId));
        queuingSubject.resyncIfChanged(state, target);
        queuingSubject.reconcileBlockNodeStreaming(target);

        assertThat(queued).hasSize(1);
        // Even if the in-memory configuration has moved on by the time the task runs, it applies the committed mode
        activeConfig.set(configWith(Map.of("blockStream.writerMode", BlockStreamWriterMode.FILE_AND_GRPC.name())));
        queued.getFirst().run();
        verify(blockNodeConnectionManager, times(1)).shutdown();
        verify(blockNodeConnectionManager, never()).start();
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
                .withConfigDataType(BlockStreamConfig.class)
                .withConfigDataType(ContractsConfig.class)
                .withConfigDataType(JumboTransactionsConfig.class);
        settings.forEach(builder::withValue);
        return builder.getOrCreateConfig();
    }
}
