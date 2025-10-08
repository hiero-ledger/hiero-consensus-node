// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.state;

import static com.swirlds.state.lifecycle.StateMetadata.computeLabel;
import static com.swirlds.state.test.fixtures.merkle.StateClassIdUtils.singletonClassId;
import static com.swirlds.state.test.fixtures.merkle.TestStateUtils.registerWithSystem;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.block.stream.output.StateChanges.Builder;
import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.config.api.Configuration;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.service.PlatformStateService;
import com.swirlds.platform.state.service.schemas.V0540PlatformStateSchema;
import com.swirlds.platform.state.service.schemas.V0540RosterBaseSchema;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapState;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.test.fixtures.merkle.MerkleStateRoot;
import com.swirlds.state.test.fixtures.merkle.singleton.SingletonNode;
import com.swirlds.state.test.fixtures.merkle.singleton.StringLeaf;
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
import org.hiero.consensus.roster.RosterStateId;

/**
 * This class is used to initialize the state of test applications. It allows to register the necessary
 * constructables and initializes the platform and roster states.
 */
public final class TestingAppStateInitializer {

    private TestingAppStateInitializer() {}

    public static void registerMerkleStateRootClassIds() {
        try {
            ConstructableRegistry registry = ConstructableRegistry.getInstance();
            registry.registerConstructable(new ClassConstructorPair(SingletonNode.class, SingletonNode::new));
            registry.registerConstructable(new ClassConstructorPair(StringLeaf.class, StringLeaf::new));
        } catch (ConstructableRegistryException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void registerConstructablesForStorage(@NonNull final Configuration configuration) {
        try {
            ConstructableRegistry registry = ConstructableRegistry.getInstance();
            registry.registerConstructable(
                    new ClassConstructorPair(VirtualMap.class, () -> new VirtualMap(configuration)));
            registry.registerConstructable(new ClassConstructorPair(
                    MerkleDbDataSourceBuilder.class, () -> new MerkleDbDataSourceBuilder(configuration)));
            registry.registerConstructable(new ClassConstructorPair(
                    VirtualNodeCache.class,
                    () -> new VirtualNodeCache(configuration.getConfigData(VirtualMapConfig.class))));
        } catch (ConstructableRegistryException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void registerConstructablesForSchemas() {
        ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registerConstructablesForSchema(registry, new V0540PlatformStateSchema(), PlatformStateService.NAME);
        registerConstructablesForSchema(registry, new V0540RosterBaseSchema(), RosterStateId.SERVICE_NAME);
    }

    private static void registerConstructablesForSchema(
            @NonNull final ConstructableRegistry registry,
            @NonNull final Schema<SemanticVersion> schema,
            @NonNull final String name) {
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .forEach(def -> registerWithSystem(new StateMetadata<>(name, def), registry, schema.getVersion()));
    }

    /**
     * Initialize the states for the given {@link MerkleNodeState}. This method will initialize both the
     * platform and roster states.
     *
     * @param state the state to initialize
     * @param configuration configuration to use
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public static List<Builder> initConsensusModuleStates(
            @NonNull final MerkleNodeState state, @NonNull final Configuration configuration) {
        List<Builder> list = new ArrayList<>();
        list.addAll(initPlatformState(state));
        list.addAll(initRosterState(state, configuration));
        return list;
    }

    /**
     * Initialize the platform state for the given {@link MerkleNodeState}. This method will initialize the
     * states used by the {@link PlatformStateService}.
     *
     * @param state the state to initialize
     * @return a list of builders for the states that were initialized. Currently, returns an empty list.
     */
    public static List<Builder> initPlatformState(@NonNull final MerkleNodeState state) {
        final var schema = new V0540PlatformStateSchema(
                config -> SemanticVersion.newBuilder().minor(1).build());
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    final var md = new StateMetadata<>(PlatformStateService.NAME, def);
                    if (def.singleton()) {
                        final String serviceName = md.serviceName();
                        final String stateKey = md.stateDefinition().stateKey();
                        initializeServiceState(
                                state,
                                md,
                                () -> new SingletonNode<>(
                                        computeLabel(serviceName, stateKey),
                                        singletonClassId(serviceName, stateKey, schema.getVersion()),
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
    public static List<Builder> initRosterState(
            @NonNull final MerkleNodeState state, @NonNull final Configuration configuration) {
        if (!(state instanceof MerkleStateRoot<?>) && !(state instanceof VirtualMapState<?>)) {
            throw new IllegalArgumentException("Can only be used with MerkleStateRoot or VirtualMapState instances");
        }
        final var schema = new V0540RosterBaseSchema();
        schema.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateId))
                .forEach(def -> {
                    final var md = new StateMetadata<>(RosterStateId.SERVICE_NAME, def);
                    if (def.singleton()) {
                        final String serviceName = md.serviceName();
                        final String stateKey = md.stateDefinition().stateKey();
                        initializeServiceState(
                                state,
                                md,
                                () -> new SingletonNode<>(
                                        computeLabel(
                                                md.serviceName(),
                                                md.stateDefinition().stateKey()),
                                        singletonClassId(serviceName, stateKey, schema.getVersion()),
                                        md.stateDefinition().valueCodec(),
                                        null));
                    } else if (def.onDisk()) {
                        initializeServiceState(state, md, () -> {
                            final var label = StateMetadata.computeLabel(RosterStateId.SERVICE_NAME, def.stateKey());
                            final var dsBuilder = new MerkleDbDataSourceBuilder(configuration, def.maxKeysHint(), 16);
                            final var virtualMap = new VirtualMap(label, dsBuilder, configuration);
                            return virtualMap;
                        });
                    } else {
                        throw new IllegalStateException(
                                "RosterService only expected to use singleton and onDisk virtual map states");
                    }
                });
        final var mockMigrationContext = mock(MigrationContext.class);
        final var writableStates = state.getWritableStates(RosterStateId.SERVICE_NAME);
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
            case MerkleStateRoot<?> ignored ->
                ((MerkleStateRoot) state).putServiceStateIfAbsent(md, nodeSupplier, n -> {});
            case VirtualMapState<?> ignored -> state.initializeState(md);
            default ->
                throw new IllegalStateException(
                        "Expecting MerkleStateRoot or VirtualMapState instance to be used for state initialization");
        }
    }
}
