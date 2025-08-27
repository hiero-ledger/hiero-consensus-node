// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.state.merkle.StateUtils.registerWithSystem;
import static java.util.Objects.requireNonNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.output.StateChanges.Builder;
import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.platform.config.AddressBookConfig;
import com.swirlds.platform.config.BasicConfig;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.MerkleStateRoot;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.merkle.singleton.StringLeaf;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.DigestType;
import org.hiero.consensus.roster.RosterStateId;

/**
 * This class is used to initialize the state of test applications. It allows to register the necessary
 * constructables and initializes the platform and roster states.
 */
public class TestingAppStateInitializer {

    public static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(AddressBookConfig.class)
            .withConfigDataType(BasicConfig.class)
            .withConfigDataType(MerkleDbConfig.class)
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .withConfigDataType(FileSystemManagerConfig.class)
            .build();

    public static final TestingAppStateInitializer DEFAULT = new TestingAppStateInitializer(CONFIGURATION);

    private final Configuration configuration;

    /**
     * Constructor for {@link TestingAppStateInitializer}
     *
     * @param configuration the configuration to use for the initialized state
     */
    public TestingAppStateInitializer(@NonNull final Configuration configuration) {
        this.configuration = requireNonNull(configuration);
    }

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
            registry.registerConstructable(
                    new ClassConstructorPair(VirtualMap.class, () -> new VirtualMap(CONFIGURATION)));
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

    /**
     * Initialize the states for the given {@link MerkleNodeState}. This method will initialize both the
     * platform and roster states.
     *
     * @param state the state to initialize
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public List<Builder> initStates(@NonNull final MerkleNodeState state) {
        List<Builder> list = new ArrayList<>();
        list.addAll(initPlatformState(state));
        list.addAll(initRosterState(state));
        return list;
    }

    /**
     * Initialize the platform state for the given {@link MerkleNodeState}. This method will initialize the
     * states used by the {@link PlatformStateService}.
     *
     * @param state the state to initialize
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public List<Builder> initPlatformState(@NonNull final MerkleNodeState state) {
        final var schema = new V0540PlatformStateSchema(
                config -> SemanticVersion.newBuilder().minor(1).build());
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    final var md = new StateMetadata<>(PlatformStateService.NAME, schema, def);
                    if (def.singleton()) {
                        initializeServiceState(
                                state,
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

    /**
     * Initialize the roster state for the given {@link MerkleNodeState}. This method will initialize the
     * states used by the {@code RosterService}.
     *
     * @param state the state to initialize
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public List<Builder> initRosterState(@NonNull final MerkleNodeState state) {
        if (!(state instanceof MerkleStateRoot<?>) && !(state instanceof VirtualMapState<?>)) {
            throw new IllegalArgumentException("Can only be used with MerkleStateRoot or VirtualMapState instances");
        }
        final var schema = new V0540RosterBaseSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> {
                    final var md = new StateMetadata<>(RosterStateId.NAME, schema, def);
                    if (def.singleton()) {
                        initializeServiceState(
                                state,
                                md,
                                () -> new SingletonNode<>(
                                        md.serviceName(),
                                        md.stateDefinition().stateKey(),
                                        md.singletonClassId(),
                                        md.stateDefinition().valueCodec(),
                                        null));
                    } else if (def.onDisk()) {
                        initializeServiceState(state, md, () -> {
                            final var tableConfig =
                                    new MerkleDbTableConfig((short) 1, DigestType.SHA_384, def.maxKeysHint(), 16);
                            final var label = StateMetadata.computeLabel(RosterStateId.NAME, def.stateKey());
                            final var dsBuilder = new MerkleDbDataSourceBuilder(tableConfig, CONFIGURATION);
                            final var virtualMap = new VirtualMap(label, dsBuilder, CONFIGURATION);
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

    // FUTURE WORK:
    // Should be removed once the MerkleStateRoot is removed along with putServiceStateIfAbsent in
    // MerkleNodeState interface
    @Deprecated
    private static void initializeServiceState(
            MerkleNodeState state, StateMetadata<?, ?> md, Supplier<? extends MerkleNode> nodeSupplier) {
        switch (state) {
            case MerkleStateRoot<?> ignored -> state.putServiceStateIfAbsent(md, nodeSupplier);
            case VirtualMapState<?> ignored -> state.initializeState(md);
            default ->
                throw new IllegalStateException(
                        "Expecting MerkleStateRoot or VirtualMapState instance to be used for state initialization");
        }
    }
}
