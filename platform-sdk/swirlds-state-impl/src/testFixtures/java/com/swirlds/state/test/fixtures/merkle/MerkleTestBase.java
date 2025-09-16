// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.hedera.hapi.platform.state.SingletonType.ENTITYIDSERVICE_I_ENTITY_ID;
import static com.hedera.hapi.platform.state.SingletonType.TOKENSERVICE_I_STAKING_NETWORK_REWARDS;
import static com.hedera.hapi.platform.state.StateKey.KeyOneOfType.FILESERVICE_I_UPGRADE_DATA_150;
import static com.hedera.hapi.platform.state.StateKey.KeyOneOfType.SCHEDULESERVICE_I_SCHEDULES_BY_EQUALITY;
import static com.hedera.hapi.platform.state.StateKey.KeyOneOfType.SINGLETON;
import static com.hedera.hapi.platform.state.StateKey.KeyOneOfType.TOKENSERVICE_I_ALIASES;
import static com.swirlds.state.lifecycle.StateMetadata.computeClassId;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForKv;
import static com.swirlds.state.merkle.StateUtils.getStateKeyForSingleton;
import static com.swirlds.state.merkle.StateUtils.getStateValue;
import static com.swirlds.virtualmap.constructable.ConstructableUtils.registerVirtualMapConstructables;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.platform.state.SingletonType;
import com.hedera.hapi.platform.state.StateKey;
import com.hedera.hapi.platform.state.StateValue;
import com.hedera.pbj.runtime.Codec;
import com.hedera.pbj.runtime.OneOf;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.config.StateCommonConfig;
import com.swirlds.common.io.config.FileSystemManagerConfig;
import com.swirlds.common.io.config.TemporaryFileConfig;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.StateUtils;
import com.swirlds.state.merkle.memory.InMemoryKey;
import com.swirlds.state.merkle.memory.InMemoryValue;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.test.fixtures.StateTestBase;
import com.swirlds.state.test.fixtures.TestArgumentUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.MockedStatic;

/**
 * This base class provides helpful methods and defaults for simplifying the other merkle related
 * tests in this and sub packages. It is highly recommended to extend from this class.
 *
 * <h1>Services</h1>
 *
 * <p>This class introduces two real services, and one bad service. The real services are called
 * (quite unhelpfully) {@link #FIRST_SERVICE} and {@link #SECOND_SERVICE}. There is also an {@link
 * #UNKNOWN_SERVICE} which is useful for tests where we are trying to look up a service that should
 * not exist.
 *
 * <p>Each service has a number of associated states, based on those defined in {@link
 * StateTestBase}. The {@link #FIRST_SERVICE} has "fruit" and "animal" states, while the {@link
 * #SECOND_SERVICE} has space, steam, and country themed states. Most of these are simple String
 * types for the key and value, but the space themed state uses Long as the key type.
 *
 * <p>This class defines all the {@link Codec}, and {@link MerkleMap}s
 * required to represent each of these. It does not create a {@link VirtualMap} automatically, but
 * does provide APIs to make it easy to create them (the {@link VirtualMap} has a lot of setup
 * complexity, and also requires a storage directory, so rather than creating these for every test
 * even if they don't need it, I just use it for virtual map specific tests).
 */
public class MerkleTestBase extends StateTestBase {

    protected final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .withConfigDataType(VirtualMapConfig.class)
            .withConfigDataType(MerkleDbConfig.class)
            .withConfigDataType(TemporaryFileConfig.class)
            .withConfigDataType(StateCommonConfig.class)
            .withConfigDataType(FileSystemManagerConfig.class)
            .build();

    /** A TEST ONLY {@link Codec} to be used with String data types */
    public static final Codec<String> STRING_CODEC = TestStringCodec.SINGLETON;
    /** A TEST ONLY {@link Codec} to be used with Long data types */
    public static final Codec<Long> LONG_CODEC = TestLongCodec.SINGLETON;

    private static final String ON_DISK_KEY_CLASS_ID_SUFFIX = "OnDiskKey";
    private static final String ON_DISK_VALUE_CLASS_ID_SUFFIX = "OnDiskValue";
    private static final String ON_DISK_KEY_SERIALIZER_CLASS_ID_SUFFIX = "OnDiskKeySerializer";
    private static final String ON_DISK_VALUE_SERIALIZER_CLASS_ID_SUFFIX = "OnDiskValueSerializer";
    private static final String IN_MEMORY_VALUE_CLASS_ID_SUFFIX = "InMemoryValue";
    private static final String SINGLETON_CLASS_ID_SUFFIX = "SingletonLeaf";
    private static final String QUEUE_NODE_CLASS_ID_SUFFIX = "QueueNode";

    /** Used by some tests that need to hash */
    protected static final MerkleCryptography CRYPTO = TestMerkleCryptoFactory.getInstance();

    // These longs are used with the "space" k/v state
    public static final long A_LONG_KEY = 0L;
    public static final long B_LONG_KEY = 1L;
    public static final long C_LONG_KEY = 2L;
    public static final long D_LONG_KEY = 3L;
    public static final long E_LONG_KEY = 4L;
    public static final long F_LONG_KEY = 5L;
    public static final long G_LONG_KEY = 6L;

    /**
     * This {@link ConstructableRegistry} is required for serialization tests. It is expensive to
     * configure it, so it is null unless {@link #setupConstructableRegistry()} has been called by
     * the test code.
     */
    protected ConstructableRegistry registry;

    // The "FRUIT" Map is part of FIRST_SERVICE
    protected String fruitLabel;
    protected MerkleMap<InMemoryKey<ProtoBytes>, InMemoryValue<ProtoBytes, ProtoBytes>> fruitMerkleMap;

    // An alternative "FRUIT" Map that is also part of FIRST_SERVICE, but based on VirtualMap
    protected String fruitVirtualLabel;
    protected VirtualMap fruitVirtualMap;

    // The "ANIMAL" map is part of FIRST_SERVICE
    protected String animalLabel;
    protected MerkleMap<InMemoryKey<ProtoBytes>, InMemoryValue<ProtoBytes, ProtoBytes>> animalMerkleMap;

    // The "SPACE" map is part of SECOND_SERVICE and uses the long-based keys
    protected String spaceLabel;
    protected MerkleMap<InMemoryKey<ProtoBytes>, InMemoryValue<ProtoBytes, ProtoBytes>> spaceMerkleMap;

    // The "STEAM" queue is part of FIRST_SERVICE
    protected String steamLabel;
    protected QueueNode<ProtoBytes> steamQueue;

    // The "COUNTRY" singleton is part of FIRST_SERVICE
    protected String countryLabel;
    protected SingletonNode<ProtoBytes> countrySingleton;

    /**
     * This static mock instance will override calls to the static methods in StateUtils
     * (specifically {@code #stateIdFor} method for now).
     */
    private static MockedStatic<StateUtils> stateUtilsMock;

    /**
     * Sets up a static mock for {@code StateUtils} before all tests, partially mocking
     * the {@code stateIdFor(String, String)} method. Real method calls are allowed unless
     * explicitly stubbed, ensuring the original behavior is retained where possible.
     *
     * <p>
     * If the real method fails, predefined mappings return specific IDs for known
     * test cases (e.g., "fruit" -> {@code FRUIT_STATE_ID}), while unmatched inputs
     * return {@code 65000}. This prevents errors when using test-specific names or keys.
     * </p>
     */
    @BeforeAll
    static void init() {
        // Static flag to bypass stateIdFor mock during calls from other mocked methods below
        AtomicBoolean bypassStateIdFor = new AtomicBoolean(false);
        stateUtilsMock = mockStatic(StateUtils.class, CALLS_REAL_METHODS);
        stateUtilsMock
                .when(() -> StateUtils.stateIdFor(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    if (bypassStateIdFor.get()) {
                        return invocation.callRealMethod();
                    }
                    try {
                        // First, try calling the real method.
                        return invocation.callRealMethod();
                    } catch (Exception e) {
                        // The real method couldn't find a valid mapping.
                        final String serviceName = invocation.getArgument(0);
                        final String stateKey = invocation.getArgument(1);

                        // Check for test-specific "made up" states.
                        if (FRUIT_SERVICE_NAME.equals(serviceName) || FRUIT_STATE_KEY.equals(stateKey)) {
                            return FRUIT_STATE_ID;
                        } else if (ANIMAL_SERVICE_NAME.equals(serviceName) || ANIMAL_STATE_KEY.equals(stateKey)) {
                            return ANIMAL_STATE_ID;
                        } else if (SPACE_SERVICE_NAME.equals(serviceName) || SPACE_STATE_KEY.equals(stateKey)) {
                            return SPACE_STATE_ID;
                        } else if (STEAM_SERVICE_NAME.equals(serviceName) || STEAM_STATE_KEY.equals(stateKey)) {
                            return STEAM_STATE_ID;
                        } else if (COUNTRY_SERVICE_NAME.equals(serviceName) || COUNTRY_STATE_KEY.equals(stateKey)) {
                            return COUNTRY_STATE_ID;
                        } else {
                            // Neither the real method nor any test mappings applied.
                            return 65000;
                        }
                    }
                });
        stateUtilsMock
                .when(() -> StateUtils.getStateKeyForKv(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    try {
                        bypassStateIdFor.set(true);
                        // First, try calling the real method.
                        return invocation.callRealMethod();
                    } catch (Exception e) {
                        // The real method couldn't find a valid mapping.
                        final String serviceName = invocation.getArgument(0);
                        final String stateKey = invocation.getArgument(1);
                        final ProtoBytes keyObject = invocation.getArgument(2);

                        // We have to map "made up" states to existing ones to keep the compatibility with the protocol
                        // The following states are chosen because they have generic `ProtoBytes` as their key type
                        if (FRUIT_SERVICE_NAME.equals(serviceName) || FRUIT_STATE_KEY.equals(stateKey)) {
                            return createStateKeyForKv(TOKENSERVICE_I_ALIASES, keyObject);
                        } else if (ANIMAL_SERVICE_NAME.equals(serviceName) || ANIMAL_STATE_KEY.equals(stateKey)) {
                            return createStateKeyForKv(SCHEDULESERVICE_I_SCHEDULES_BY_EQUALITY, keyObject);
                        } else {
                            // Neither the real method nor any test mappings applied.
                            return 65000;
                        }
                    } finally {
                        bypassStateIdFor.set(false);
                    }
                });
        stateUtilsMock
                .when(() -> StateUtils.getStateValue(anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    try {
                        bypassStateIdFor.set(true);
                        // First, try calling the real method.
                        return invocation.callRealMethod();
                    } catch (Exception e) {
                        // The real method couldn't find a valid mapping.
                        final String serviceName = invocation.getArgument(0);
                        final String stateKey = invocation.getArgument(1);
                        final ProtoBytes valueObject = invocation.getArgument(2);

                        // We have to map "made up" states to existing ones to keep the compatibility with the protocol
                        // The following states are chosen because they have generic `ProtoBytes` as their value type
                        if (FRUIT_SERVICE_NAME.equals(serviceName) || FRUIT_STATE_KEY.equals(stateKey)) {
                            return createStateValue(
                                    StateValue.ValueOneOfType.FREEZESERVICE_I_UPGRADE_FILE_HASH, valueObject);
                        } else if (ANIMAL_SERVICE_NAME.equals(serviceName) || ANIMAL_STATE_KEY.equals(stateKey)) {
                            return createStateValue(StateValue.ValueOneOfType.HISTORYSERVICE_I_LEDGER_ID, valueObject);
                        } else if (SPACE_SERVICE_NAME.equals(serviceName) || SPACE_STATE_KEY.equals(stateKey)) {
                            return createStateValue(
                                    com.hedera.hapi.platform.state.StateValue.ValueOneOfType
                                            .FILESERVICE_I_UPGRADE_DATA_150,
                                    valueObject);
                        } else if (STEAM_SERVICE_NAME.equals(serviceName) || STEAM_STATE_KEY.equals(stateKey)) {
                            return createStateValue(
                                    com.hedera.hapi.platform.state.StateValue.ValueOneOfType
                                            .FILESERVICE_I_UPGRADE_DATA_151,
                                    valueObject);
                        } else if (COUNTRY_SERVICE_NAME.equals(serviceName) || COUNTRY_STATE_KEY.equals(stateKey)) {
                            return createStateValue(
                                    com.hedera.hapi.platform.state.StateValue.ValueOneOfType
                                            .FILESERVICE_I_UPGRADE_DATA_152,
                                    valueObject);
                        } else {
                            // Neither the real method nor any test mappings applied.
                            return 65000;
                        }
                    } finally {
                        bypassStateIdFor.set(false);
                    }
                });
        stateUtilsMock
                .when(() -> StateUtils.getStateKeyForQueue(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    try {
                        // First, try calling the real method.
                        bypassStateIdFor.set(true);
                        return invocation.callRealMethod();
                    } catch (Exception e) {
                        // The real method couldn't find a valid mapping.
                        final String serviceName = invocation.getArgument(0);
                        final String stateKey = invocation.getArgument(1);
                        final Long keyObject = invocation.getArgument(2);

                        // We have to map "made up" states to existing ones to keep the compatibility with the protocol
                        // The following states are chosen because they have generic `ProtoBytes` as their key type
                        if (STEAM_SERVICE_NAME.equals(serviceName) || STEAM_STATE_KEY.equals(stateKey)) {
                            return createStateKeyForQueue(FILESERVICE_I_UPGRADE_DATA_150, keyObject);
                        } else {
                            // Neither the real method nor any test mappings applied.
                            return 65000;
                        }
                    } finally {
                        bypassStateIdFor.set(false);
                    }
                });
        stateUtilsMock
                .when(() -> StateUtils.getStateKeyForSingleton(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    try {
                        bypassStateIdFor.set(true);
                        // First, try calling the real method.
                        return invocation.callRealMethod();
                    } catch (Exception e) {
                        // The real method couldn't find a valid mapping.
                        final String serviceName = invocation.getArgument(0);
                        final String stateKey = invocation.getArgument(1);

                        // We have to map "made up" states to existing ones to keep the compatibility with the protocol
                        // The following states are chosen because they have generic `ProtoBytes` as their key type
                        if (SPACE_SERVICE_NAME.equals(serviceName) || SPACE_STATE_KEY.equals(stateKey)) {
                            return createStateKeyForSingleton(ENTITYIDSERVICE_I_ENTITY_ID);
                        } else if (STEAM_SERVICE_NAME.equals(serviceName) || STEAM_STATE_KEY.equals(stateKey)) {
                            return createStateKeyForSingleton(SingletonType.FILESERVICE_I_UPGRADE_DATA_150);
                        } else if (COUNTRY_SERVICE_NAME.equals(serviceName) || COUNTRY_STATE_KEY.equals(stateKey)) {
                            return createStateKeyForSingleton(TOKENSERVICE_I_STAKING_NETWORK_REWARDS);
                        } else {
                            // Neither the real method nor any test mappings applied.
                            return 65000;
                        }
                    } finally {
                        bypassStateIdFor.set(false);
                    }
                });
    }

    private static Bytes createStateKeyForKv(StateKey.KeyOneOfType type, ProtoBytes keyObject) {
        return StateKey.PROTOBUF.toBytes(new StateKey(new OneOf<>(type, keyObject)));
    }

    private static Bytes createStateKeyForSingleton(SingletonType type) {
        return StateKey.PROTOBUF.toBytes(new StateKey(new OneOf<>(SINGLETON, type)));
    }

    private static Bytes createStateKeyForQueue(StateKey.KeyOneOfType type, Long index) {
        return StateKey.PROTOBUF.toBytes(new StateKey(new OneOf<>(type, index)));
    }

    private static StateValue createStateValue(StateValue.ValueOneOfType type, ProtoBytes valueObject) {
        return new StateValue(new OneOf<>(type, valueObject));
    }

    /** Sets up the "Fruit" merkle map, label, and metadata. */
    protected void setupFruitMerkleMap() {
        fruitLabel = StateMetadata.computeLabel(FIRST_SERVICE, FRUIT_STATE_KEY);
        fruitMerkleMap = createMerkleMap(fruitLabel);
    }

    /** Sets up the "Fruit" virtual map, label, and metadata. */
    protected void setupFruitVirtualMap() {
        fruitVirtualLabel = StateMetadata.computeLabel(FIRST_SERVICE, FRUIT_STATE_KEY);
        fruitVirtualMap = createVirtualMap(fruitVirtualLabel);
    }

    protected static long queueNodeClassId(String stateKey) {
        return computeClassId(FIRST_SERVICE, stateKey, TEST_VERSION, QUEUE_NODE_CLASS_ID_SUFFIX);
    }

    protected static long inMemoryValueClassId(String stateKey) {
        return computeClassId(FIRST_SERVICE, stateKey, TEST_VERSION, IN_MEMORY_VALUE_CLASS_ID_SUFFIX);
    }

    protected static long singletonClassId(String stateKey) {
        return computeClassId(FIRST_SERVICE, stateKey, TEST_VERSION, SINGLETON_CLASS_ID_SUFFIX);
    }

    /** Sets up the "Animal" merkle map, label, and metadata. */
    protected void setupAnimalMerkleMap() {
        animalLabel = StateMetadata.computeLabel(FIRST_SERVICE, ANIMAL_STATE_KEY);
        animalMerkleMap = createMerkleMap(animalLabel);
    }

    /** Sets up the "Space" merkle map, label, and metadata. */
    protected void setupSpaceMerkleMap() {
        spaceLabel = StateMetadata.computeLabel(SECOND_SERVICE, SPACE_STATE_KEY);
        spaceMerkleMap = createMerkleMap(spaceLabel);
    }

    protected void setupSingletonCountry() {
        countryLabel = StateMetadata.computeLabel(FIRST_SERVICE, COUNTRY_STATE_KEY);
        countrySingleton = new SingletonNode<>(
                FIRST_SERVICE,
                COUNTRY_STATE_KEY,
                computeClassId(FIRST_SERVICE, COUNTRY_STATE_KEY, TEST_VERSION, SINGLETON_CLASS_ID_SUFFIX),
                ProtoBytes.PROTOBUF,
                AUSTRALIA);
    }

    protected void setupSteamQueue() {
        steamLabel = StateMetadata.computeLabel(FIRST_SERVICE, STEAM_STATE_KEY);
        steamQueue = new QueueNode<>(
                FIRST_SERVICE,
                STEAM_STATE_KEY,
                computeClassId(FIRST_SERVICE, STEAM_STATE_KEY, TEST_VERSION, QUEUE_NODE_CLASS_ID_SUFFIX),
                computeClassId(FIRST_SERVICE, STEAM_STATE_KEY, TEST_VERSION, SINGLETON_CLASS_ID_SUFFIX),
                ProtoBytes.PROTOBUF);
    }

    /** Sets up the {@link #registry}, ready to be used for serialization tests */
    protected void setupConstructableRegistry() {
        // Unfortunately, we need to configure the ConstructableRegistry for serialization tests and
        // even for basic usage of the MerkleMap (it uses it internally to make copies of internal
        // nodes).
        try {
            registry = ConstructableRegistry.getInstance();

            // It may have been configured during some other test, so we reset it
            registry.reset();
            registry.registerConstructables("com.swirlds.merklemap");
            registry.registerConstructables("com.swirlds.merkledb");
            registry.registerConstructables("com.swirlds.fcqueue");
            registry.registerConstructables("com.swirlds.virtualmap");
            registry.registerConstructables("com.swirlds.common.merkle");
            registry.registerConstructables("com.swirlds.common");
            registry.registerConstructables("org.hiero");
            registry.registerConstructables("com.swirlds.merkle");
            registry.registerConstructables("com.swirlds.merkle.tree");

            ConstructableRegistry.getInstance()
                    .registerConstructable(new ClassConstructorPair(
                            MerkleDbDataSourceBuilder.class, () -> new MerkleDbDataSourceBuilder(CONFIGURATION)));
            registerVirtualMapConstructables(CONFIGURATION);
        } catch (ConstructableRegistryException ex) {
            throw new AssertionError(ex);
        }
    }

    /** Creates a new arbitrary merkle map with the given label. */
    protected <K extends Comparable<ProtoBytes>, V>
            MerkleMap<InMemoryKey<ProtoBytes>, InMemoryValue<ProtoBytes, V>> createMerkleMap(String label) {
        final var map = new MerkleMap<InMemoryKey<ProtoBytes>, InMemoryValue<ProtoBytes, V>>();
        map.setLabel(label);
        return map;
    }

    /** Creates a new arbitrary virtual map with the given label, storageDir, and metadata */
    @SuppressWarnings("unchecked")
    protected VirtualMap createVirtualMap(String label) {
        final var builder = new MerkleDbDataSourceBuilder(CONFIGURATION, 100, 0);
        return new VirtualMap(label, builder, CONFIGURATION);
    }

    /** A convenience method for adding a k/v pair to a merkle map */
    protected void addKvState(
            MerkleMap<InMemoryKey<ProtoBytes>, InMemoryValue<ProtoBytes, ProtoBytes>> map,
            long inMemoryValueClassId,
            Codec<ProtoBytes> keyCodec,
            Codec<ProtoBytes> valueCodec,
            ProtoBytes key,
            ProtoBytes value) {
        final var k = new InMemoryKey<>(key);
        map.put(k, new InMemoryValue<>(inMemoryValueClassId, keyCodec, valueCodec, k, value));
    }

    /** A convenience method for adding a singleton state to a virtual map */
    protected void addSingletonState(VirtualMap map, String serviceName, String stateKey, ProtoBytes value) {
        map.put(
                getStateKeyForSingleton(serviceName, stateKey),
                getStateValue(serviceName, stateKey, value),
                StateValue.PROTOBUF);
    }

    /** A convenience method for adding a k/v state to a virtual map */
    protected void addKvState(VirtualMap map, String serviceName, String stateKey, ProtoBytes key, ProtoBytes value) {
        map.put(
                getStateKeyForKv(serviceName, stateKey, key),
                getStateValue(serviceName, stateKey, value),
                StateValue.PROTOBUF);
    }

    /** A convenience method used to serialize a merkle tree */
    protected byte[] writeTree(@NonNull final MerkleNode tree, @NonNull final Path tempDir) throws IOException {
        final var byteOutputStream = new ByteArrayOutputStream();
        try (final var out = new MerkleDataOutputStream(byteOutputStream)) {
            out.writeMerkleTree(tempDir, tree);
        }
        return byteOutputStream.toByteArray();
    }

    /** A convenience method used to deserialize a merkle tree */
    protected <T extends MerkleNode> T parseTree(@NonNull final byte[] state, @NonNull final Path tempDir)
            throws IOException {
        final var byteInputStream = new ByteArrayInputStream(state);
        try (final var in = new MerkleDataInputStream(byteInputStream)) {
            return in.readMerkleTree(CONFIGURATION, tempDir, 100);
        }
    }

    public static Stream<Arguments> illegalServiceNames() {
        return TestArgumentUtils.illegalIdentifiers();
    }

    public static Stream<Arguments> legalServiceNames() {
        return TestArgumentUtils.legalIdentifiers();
    }

    @AfterEach
    void cleanUp() {
        if (fruitVirtualMap != null && fruitVirtualMap.getReservationCount() > -1) {
            fruitVirtualMap.release();
        }
        MerkleDbTestUtils.assertAllDatabasesClosed();
    }

    @AfterAll
    static void cleanUpStaticMocks() {
        stateUtilsMock.close();
    }
}
