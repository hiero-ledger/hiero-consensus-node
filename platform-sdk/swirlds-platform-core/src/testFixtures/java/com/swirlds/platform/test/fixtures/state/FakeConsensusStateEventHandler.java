// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.state.merkle.StateUtils.registerWithSystem;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.RosterStateId;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.platform.system.BasicSoftwareVersion;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.consensus.model.crypto.DigestType;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

public enum FakeConsensusStateEventHandler implements ConsensusStateEventHandler<MerkleNodeState> {
    FAKE_CONSENSUS_STATE_EVENT_HANDLER;

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(AddressBookConfig.class)
            .withConfigDataType(BasicConfig.class)
            .withConfigDataType(MerkleDbConfig.class)
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .withConfigDataType(FileSystemManagerConfig.class)
            .build();

    /**
     * Register the class IDs for the {@link MerkleStateRoot} and its required children, specifically those
     * used by the {@link PlatformStateService} and {@code RosterService}.
     */
    public static void registerMerkleStateRootClassIds() {
        try {
            ConstructableRegistry registry = ConstructableRegistry.getInstance();
            registry.registerConstructable(
                    new ClassConstructorPair(TestMerkleStateRoot.class, TestMerkleStateRoot::new));
            registry.registerConstructable(new ClassConstructorPair(SingletonNode.class, SingletonNode::new));
            registry.registerConstructable(new ClassConstructorPair(StringLeaf.class, StringLeaf::new));
            registry.registerConstructable(new ClassConstructorPair(
                    VirtualMap.class, () -> new VirtualMap(FakeConsensusStateEventHandler.CONFIGURATION)));
            registry.registerConstructable(new ClassConstructorPair(
                    MerkleDbDataSourceBuilder.class, () -> new MerkleDbDataSourceBuilder(CONFIGURATION)));
            registry.registerConstructable(new ClassConstructorPair(
                    VirtualNodeCache.class,
                    () -> new VirtualNodeCache(CONFIGURATION.getConfigData(VirtualMapConfig.class))));
            registerConstructablesForSchema(registry, new V0540PlatformStateSchema(), PlatformStateService.NAME);
            registerConstructablesForSchema(registry, new V0540RosterBaseSchema(), RosterStateId.NAME);
        } catch (ConstructableRegistryException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void registerConstructablesForSchema(
            @NonNull final ConstructableRegistry registry, @NonNull final Schema schema, @NonNull final String name) {
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> registerWithSystem(new StateMetadata<>(name, schema, def), registry));
    }

    public List<StateChanges.Builder> initStates(@NonNull final MerkleNodeState state) {
        List<StateChanges.Builder> list = new ArrayList<>();
        list.addAll(initPlatformState(state));
        list.addAll(initRosterState(state));
        return list;
    }

    public List<StateChanges.Builder> initPlatformState(@NonNull final MerkleNodeState state) {
        final var schema = new V0540PlatformStateSchema(config -> new BasicSoftwareVersion(1));
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    final var md = new StateMetadata<>(PlatformStateService.NAME, schema, def);
                    if (def.singleton()) {
                        state.putServiceStateIfAbsent(
                                md,
                                () -> new SingletonNode<>(
                                        md.serviceName(),
                                        md.stateDefinition().stateKey(),
                                        md.singletonClassId(),
                                        md.stateDefinition().valueCodec(),
                                        null));
                    } else {
                        throw new IllegalStateException("PlatformStateService only expected to use singleton states");
                    }
                });
        final var mockMigrationContext = mock(MigrationContext.class);
        final var writableStates = state.getWritableStates(PlatformStateService.NAME);
        given(mockMigrationContext.newStates()).willReturn(writableStates);
        schema.migrate(mockMigrationContext);
        ((CommittableWritableStates) writableStates).commit();
        return Collections.emptyList();
    }

    public List<StateChanges.Builder> initRosterState(@NonNull final MerkleNodeState state) {
        if (!(state instanceof MerkleStateRoot<?> merkleStateRoot)) {
            throw new IllegalArgumentException("Can only be used with MerkleStateRoot instances");
        }
        final var schema = new V0540RosterBaseSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    final var md = new StateMetadata<>(RosterStateId.NAME, schema, def);
                    if (def.singleton()) {
                        state.putServiceStateIfAbsent(
                                md,
                                () -> new SingletonNode<>(
                                        md.serviceName(),
                                        md.stateDefinition().stateKey(),
                                        md.singletonClassId(),
                                        md.stateDefinition().valueCodec(),
                                        null));
                    } else if (def.onDisk()) {
                        state.putServiceStateIfAbsent(md, () -> {
                            final var keySerializer = new OnDiskKeySerializer<>(
                                    md.onDiskKeySerializerClassId(),
                                    md.onDiskKeyClassId(),
                                    md.stateDefinition().keyCodec());
                            final var valueSerializer = new OnDiskValueSerializer<>(
                                    md.onDiskValueSerializerClassId(),
                                    md.onDiskValueClassId(),
                                    md.stateDefinition().valueCodec());
                            final var tableConfig =
                                    new MerkleDbTableConfig((short) 1, DigestType.SHA_384, def.maxKeysHint(), 16);
                            final var label = StateMetadata.computeLabel(RosterStateId.NAME, def.stateKey());
                            final var dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
                            final var virtualMap =
                                    new VirtualMap<>(label, keySerializer, valueSerializer, dsBuilder, CONFIGURATION);
                            return virtualMap;
                        });
                    } else {
                        throw new IllegalStateException(
                                "RosterService only expected to use singleton and onDisk virtual map states");
                    }
                });
        final var mockMigrationContext = mock(MigrationContext.class);
        final var writableStates = state.getWritableStates(RosterStateId.NAME);
        given(mockMigrationContext.newStates()).willReturn(writableStates);
        schema.migrate(mockMigrationContext);
        ((CommittableWritableStates) writableStates).commit();
        return Collections.emptyList();
    }

    @Override
    public void onPreHandle(
            @NonNull Event event,
            @NonNull MerkleNodeState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        // no-op
    }

    @Override
    public void onHandleConsensusRound(
            @NonNull Round round,
            @NonNull MerkleNodeState state,
            @NonNull Consumer<ScopedSystemTransaction<StateSignatureTransaction>> stateSignatureTransactionCallback) {
        // no-op
    }

    @Override
    public boolean onSealConsensusRound(@NonNull Round round, @NonNull MerkleNodeState state) {
        // Touch this round
        round.getRoundNum();
        return true;
    }

    @Override
    public void onStateInitialized(
            @NonNull final MerkleNodeState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        // no-op
    }

    @Override
    public void onUpdateWeight(
            @NonNull MerkleNodeState state, @NonNull AddressBook configAddressBook, @NonNull PlatformContext context) {
        // no-op
    }

    @Override
    public void onNewRecoveredState(@NonNull MerkleNodeState recoveredState) {
        // no-op
    }
}
