// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static com.swirlds.platform.test.fixtures.state.TestPlatformStateFacade.TEST_PLATFORM_STATE_FACADE;
import static com.swirlds.state.merkle.MerkleStateRoot.MINIMUM_SUPPORTED_VERSION;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.node.app.services.MigrationStateChanges;
import com.hedera.node.config.data.HederaConfig;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.config.StateCommonConfig_;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.utility.LegacyTemporaryFileBuilder;
import com.swirlds.common.merkle.utility.MerkleTreeSnapshotReader;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.platform.config.StateConfig_;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.platform.test.fixtures.state.MerkleTestBase;
import com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator;
import com.swirlds.platform.test.fixtures.state.TestHederaVirtualMapState;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.merkle.disk.OnDiskReadableKVState;
import com.swirlds.state.merkle.disk.OnDiskWritableKVState;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableQueueState;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableQueueState;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.test.fixtures.merkle.TestSchema;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.config.VirtualMapConfig_;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.config.CryptoConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SerializationTest extends MerkleTestBase {

    private static final Logger logger = LogManager.getLogger(RandomSignedStateGenerator.class);

    private Path dir;
    private Configuration config;

    @Mock
    private MigrationStateChanges migrationStateChanges;

    @Mock
    private StartupNetworks startupNetworks;

    @BeforeEach
    void setUp() throws IOException {
        setupConstructableRegistry();

        this.config = new TestConfigBuilder()
                .withSource(new SimpleConfigSource()
                        .withValue(VirtualMapConfig_.FLUSH_INTERVAL, 1 + "")
                        .withValue(VirtualMapConfig_.COPY_FLUSH_CANDIDATE_THRESHOLD, 1 + ""))
                .withConfigDataType(VirtualMapConfig.class)
                .withConfigDataType(HederaConfig.class)
                .withConfigDataType(CryptoConfig.class)
                .getOrCreateConfig();
        this.dir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(config);
    }

    Schema createV1Schema() {
        return new TestSchema(v1) {
            @NonNull
            @Override
            @SuppressWarnings("rawtypes")
            public Set<StateDefinition> statesToCreate() {
                final var fruitDef = StateDefinition.inMemory(FRUIT_STATE_KEY, ProtoBytes.PROTOBUF, STRING_CODEC);
                final var animalDef = StateDefinition.onDisk(ANIMAL_STATE_KEY, ProtoBytes.PROTOBUF, STRING_CODEC, 100);
                final var countryDef = StateDefinition.singleton(COUNTRY_STATE_KEY, STRING_CODEC);
                final var steamDef = StateDefinition.queue(STEAM_STATE_KEY, STRING_CODEC);
                return Set.of(fruitDef, animalDef, countryDef, steamDef);
            }

            @Override
            public void migrate(@NonNull final MigrationContext ctx) {
                final var newStates = ctx.newStates();
                final WritableKVState<ProtoBytes, ProtoBytes> fruit = newStates.get(FRUIT_STATE_KEY);
                fruit.put(A_KEY, APPLE);
                fruit.put(B_KEY, BANANA);
                fruit.put(C_KEY, CHERRY);
                fruit.put(D_KEY, DATE);
                fruit.put(E_KEY, EGGPLANT);
                fruit.put(F_KEY, FIG);
                fruit.put(G_KEY, GRAPE);

                final OnDiskWritableKVState<ProtoBytes, ProtoBytes> animals =
                        (OnDiskWritableKVState<ProtoBytes, ProtoBytes>)
                                (OnDiskWritableKVState) newStates.get(ANIMAL_STATE_KEY);
                animals.put(A_KEY, AARDVARK);
                animals.put(B_KEY, BEAR);
                animals.put(C_KEY, CUTTLEFISH);
                animals.put(D_KEY, DOG);
                animals.put(E_KEY, EMU);
                animals.put(F_KEY, FOX);
                animals.put(G_KEY, GOOSE);
                animals.commit();

                final WritableSingletonState<ProtoBytes> country = newStates.getSingleton(COUNTRY_STATE_KEY);
                country.put(CHAD);

                final WritableQueueState<ProtoBytes> steam = newStates.getQueue(STEAM_STATE_KEY);
                steam.add(ART);
                steam.add(BIOLOGY);
                steam.add(CHEMISTRY);
                steam.add(DISCIPLINE);
                steam.add(ECOLOGY);
                steam.add(FIELDS);
                steam.add(GEOMETRY);
            }
        };
    }

    private void forceFlush(ReadableKVState<?, ?> state) {
        if (state instanceof OnDiskReadableKVState<?, ?>) {
            try {
                Field vmField = OnDiskReadableKVState.class.getDeclaredField("virtualMap");
                vmField.setAccessible(true);
                VirtualMap vm = (VirtualMap) vmField.get(state);

                if (vm.size() > 1) {
                    vm.enableFlush();
                    vm.release();
                    vm.waitUntilFlushed();
                }
            } catch (IllegalAccessException | NoSuchFieldException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * In this test scenario, we have a genesis setup where we create FRUIT and ANIMALS and COUNTRY,
     * save them to disk, and then load them back in, and verify everything was loaded correctly.
     * <br/>
     * This tests has two modes: one where we force flush the VMs to disk and one where we don't.
     * When it forces disk flush, it makes sure that the data gets to the datasource from cache and persisted in the table.
     * When the flush is not forced, the data remains in cache which has its own serialization mechanism
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void simpleReadAndWrite(boolean forceFlush) throws IOException, ConstructableRegistryException {
        final var schemaV1 = createV1Schema();
        final var originalTree = createMerkleHederaState(schemaV1);

        // When we serialize it to bytes and deserialize it back into a tree
        MerkleNodeState copy = originalTree.copy(); // make a copy to make VM flushable
        final byte[] serializedBytes;
        if (forceFlush) {
            // Force flush the VMs to disk to test serialization and deserialization
            forceFlush(originalTree.getReadableStates(FIRST_SERVICE).get(ANIMAL_STATE_KEY));
            copy.copy().release(); // make a fast copy because we can only write to disk an immutable copy
            CRYPTO.digestTreeSync(copy.getRoot());
            serializedBytes = writeTree(copy.getRoot(), dir);
        } else {
            CRYPTO.digestTreeSync(copy.getRoot());
            serializedBytes = writeTree(originalTree.getRoot(), dir);
        }

        final TestHederaVirtualMapState loadedTree = loadedMerkleTree(schemaV1, serializedBytes);

        assertTree(loadedTree);
        try {
            originalTree.release();
        } catch (Exception e) {
            logger.error("Exception while releasing state", e);
        }
        copy.release();
        loadedTree.release();
    }

    @Test
    void snapshot() throws IOException {
        final var schemaV1 = createV1Schema();
        final var originalTree = createMerkleHederaState(schemaV1);
        final var tempDir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(config);
        final var configBuilder = new TestConfigBuilder()
                .withValue(StateConfig_.SIGNED_STATE_DISK, 1)
                .withValue(
                        StateCommonConfig_.SAVED_STATE_DIRECTORY,
                        tempDir.toFile().toString());
        final PlatformContext context = TestPlatformContextBuilder.create()
                .withConfiguration(configBuilder.getOrCreateConfig())
                .withTime(new FakeTime())
                .build();

        originalTree.init(
                context.getTime(),
                context.getConfiguration(),
                context.getMetrics(),
                context.getMerkleCryptography(),
                () -> TEST_PLATFORM_STATE_FACADE
                        .consensusSnapshotOf(originalTree)
                        .round());

        // prepare the tree and create a snapshot
        originalTree.copy().release();
        originalTree.computeHash();
        originalTree.createSnapshot(tempDir);

        // Restore to a fresh MerkleDb instance
        MerkleDb.resetDefaultInstancePath();
        final MerkleNodeState state =
                originalTree.loadSnapshot(tempDir.resolve(MerkleTreeSnapshotReader.SIGNED_STATE_FILE_NAME));
        initServices(schemaV1, state);
        assertTree(state);

        originalTree.release();
        state.release();
    }

    /**
     * This test scenario is trickier, and it's designed to reproduce <a href="https://github.com/hashgraph/hedera-services/issues/13335">#13335: OnDiskKeySerializer uses wrong classId for OnDiskKey.</a>
     * This issue can be reproduced only if at first it gets flushed to disk, then it gets loaded back in, and this time it remains in cache.
     * After it gets saved to disk again, and then loaded back in, it results in ClassCastException due to incorrect classId.
     */
    @Test
    void dualReadAndWrite() throws IOException, ConstructableRegistryException {
        final var schemaV1 = createV1Schema();
        final var originalTree = createMerkleHederaState(schemaV1);

        MerkleNodeState copy = originalTree.copy(); // make a copy to make VM flushable

        forceFlush(originalTree.getReadableStates(FIRST_SERVICE).get(ANIMAL_STATE_KEY));
        copy.copy().release(); // make a fast copy because we can only write to disk an immutable copy
        CRYPTO.digestTreeSync(copy.getRoot());
        final byte[] serializedBytes = writeTree(copy.getRoot(), dir);

        TestHederaVirtualMapState loadedTree = loadedMerkleTree(schemaV1, serializedBytes);
        ((OnDiskReadableKVState) originalTree.getReadableStates(FIRST_SERVICE).get(ANIMAL_STATE_KEY)).reset();
        populateVmCache(loadedTree);

        loadedTree.copy().release(); // make a copy to store it to disk

        CRYPTO.digestTreeSync(loadedTree.getRoot());
        // refreshing the dir
        dir = LegacyTemporaryFileBuilder.buildTemporaryDirectory(config);
        final byte[] serializedBytesWithCache = writeTree(loadedTree.getRoot(), dir);

        // let's load it again and see if it works
        TestHederaVirtualMapState loadedTreeWithCache = loadedMerkleTree(schemaV1, serializedBytesWithCache);
        ((OnDiskReadableKVState)
                        loadedTreeWithCache.getReadableStates(FIRST_SERVICE).get(ANIMAL_STATE_KEY))
                .reset();

        assertTree(loadedTreeWithCache);
        try {
            originalTree.release();
        } catch (Exception e) {
            logger.error("Exception while releasing state", e);
        }
        loadedTree.release();
        loadedTreeWithCache.release();
        copy.release();
    }

    private TestHederaVirtualMapState loadedMerkleTree(Schema schemaV1, byte[] serializedBytes) throws IOException {
        final VirtualMap virtualMap = parseTree(serializedBytes, dir);
        final TestHederaVirtualMapState loadedTree = new TestHederaVirtualMapState(virtualMap);
        initServices(schemaV1, loadedTree);

        return loadedTree;
    }

    private void initServices(Schema schemaV1, MerkleNodeState loadedTree) {
        final var newRegistry =
                new MerkleSchemaRegistry(registry, FIRST_SERVICE, DEFAULT_CONFIG, new SchemaApplications());
        newRegistry.register(schemaV1);
        newRegistry.migrate(
                loadedTree,
                schemaV1.getVersion(),
                schemaV1.getVersion(),
                config,
                config,
                new HashMap<>(),
                migrationStateChanges,
                startupNetworks,
                TEST_PLATFORM_STATE_FACADE);
        loadedTree.getRoot().migrate(CONFIGURATION, MINIMUM_SUPPORTED_VERSION);
    }

    private MerkleNodeState createMerkleHederaState(Schema schemaV1) {
        final SignedState randomState =
                new RandomSignedStateGenerator().setRound(1).build();

        final var originalTree = randomState.getState();
        final var originalRegistry =
                new MerkleSchemaRegistry(registry, FIRST_SERVICE, DEFAULT_CONFIG, new SchemaApplications());
        originalRegistry.register(schemaV1);
        originalRegistry.migrate(
                originalTree,
                null,
                v1,
                config,
                config,
                new HashMap<>(),
                migrationStateChanges,
                startupNetworks,
                TEST_PLATFORM_STATE_FACADE);
        return originalTree;
    }

    private static void populateVmCache(State loadedTree) {
        final var states = loadedTree.getWritableStates(FIRST_SERVICE);
        final WritableKVState<ProtoBytes, ProtoBytes> animalState = states.get(ANIMAL_STATE_KEY);
        assertThat(animalState.get(A_KEY)).isEqualTo(AARDVARK);
        assertThat(animalState.get(B_KEY)).isEqualTo(BEAR);
        assertThat(animalState.get(C_KEY)).isEqualTo(CUTTLEFISH);
        assertThat(animalState.get(D_KEY)).isEqualTo(DOG);
        assertThat(animalState.get(E_KEY)).isEqualTo(EMU);
        assertThat(animalState.get(F_KEY)).isEqualTo(FOX);
        assertThat(animalState.get(G_KEY)).isEqualTo(GOOSE);
    }

    private static void assertTree(State loadedTree) {
        final var states = loadedTree.getReadableStates(FIRST_SERVICE);
        final ReadableKVState<ProtoBytes, ProtoBytes> fruitState = states.get(FRUIT_STATE_KEY);
        assertThat(fruitState.get(A_KEY)).isEqualTo(APPLE);
        assertThat(fruitState.get(B_KEY)).isEqualTo(BANANA);
        assertThat(fruitState.get(C_KEY)).isEqualTo(CHERRY);
        assertThat(fruitState.get(D_KEY)).isEqualTo(DATE);
        assertThat(fruitState.get(E_KEY)).isEqualTo(EGGPLANT);
        assertThat(fruitState.get(F_KEY)).isEqualTo(FIG);
        assertThat(fruitState.get(G_KEY)).isEqualTo(GRAPE);

        final ReadableKVState<ProtoBytes, ProtoBytes> animalState = states.get(ANIMAL_STATE_KEY);
        assertThat(animalState.get(A_KEY)).isEqualTo(AARDVARK);
        assertThat(animalState.get(B_KEY)).isEqualTo(BEAR);
        assertThat(animalState.get(C_KEY)).isEqualTo(CUTTLEFISH);
        assertThat(animalState.get(D_KEY)).isEqualTo(DOG);
        assertThat(animalState.get(E_KEY)).isEqualTo(EMU);
        assertThat(animalState.get(F_KEY)).isEqualTo(FOX);
        assertThat(animalState.get(G_KEY)).isEqualTo(GOOSE);

        final ReadableSingletonState<ProtoBytes> countryState = states.getSingleton(COUNTRY_STATE_KEY);
        assertThat(countryState.get()).isEqualTo(CHAD);

        final ReadableQueueState<ProtoBytes> steamState = states.getQueue(STEAM_STATE_KEY);
        assertThat(steamState.iterator())
                .toIterable()
                .containsExactly(ART, BIOLOGY, CHEMISTRY, DISCIPLINE, ECOLOGY, FIELDS, GEOMETRY);
    }
}
