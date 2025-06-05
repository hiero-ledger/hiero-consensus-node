// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.test.fixtures.merkle;

import static com.swirlds.state.lifecycle.StateMetadata.computeClassId;
import static com.swirlds.virtualmap.constructable.ConstructableUtils.registerVirtualMapConstructables;

import com.hedera.pbj.runtime.Codec;
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
import com.swirlds.merkledb.MerkleDb;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.merkledb.MerkleDbTableConfig;
import com.swirlds.merkledb.config.MerkleDbConfig;
import com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.disk.OnDiskKey;
import com.swirlds.state.merkle.disk.OnDiskKeySerializer;
import com.swirlds.state.merkle.disk.OnDiskValue;
import com.swirlds.state.merkle.disk.OnDiskValueSerializer;
import com.swirlds.state.merkle.memory.InMemoryKey;
import com.swirlds.state.merkle.memory.InMemoryValue;
import com.swirlds.state.merkle.queue.QueueNode;
import com.swirlds.state.merkle.singleton.SingletonNode;
import com.swirlds.state.test.fixtures.StateTestBase;
import com.swirlds.state.test.fixtures.TestArgumentUtils;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.serialize.KeySerializer;
import com.swirlds.virtualmap.serialize.ValueSerializer;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.DigestType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.provider.Arguments;

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

    @TempDir
    private Path virtualDbPath;

    // The "FRUIT" Map is part of FIRST_SERVICE
    protected String fruitLabel;
    protected MerkleMap<InMemoryKey<String>, InMemoryValue<String, String>> fruitMerkleMap;

    // An alternative "FRUIT" Map that is also part of FIRST_SERVICE, but based on VirtualMap
    protected String fruitVirtualLabel;
    protected VirtualMap<OnDiskKey<String>, OnDiskValue<String>> fruitVirtualMap;

    // The "ANIMAL" map is part of FIRST_SERVICE
    protected String animalLabel;
    protected MerkleMap<InMemoryKey<String>, InMemoryValue<String, String>> animalMerkleMap;

    // The "SPACE" map is part of SECOND_SERVICE and uses the long-based keys
    protected String spaceLabel;
    protected MerkleMap<InMemoryKey<Long>, InMemoryValue<Long, Long>> spaceMerkleMap;

    // The "STEAM" queue is part of FIRST_SERVICE
    protected String steamLabel;
    protected QueueNode<String> steamQueue;

    // The "COUNTRY" singleton is part of FIRST_SERVICE
    protected String countryLabel;
    protected SingletonNode<String> countrySingleton;

    /** Sets up the "Fruit" merkle map, label, and metadata. */
    protected void setupFruitMerkleMap() {
        fruitLabel = StateMetadata.computeLabel(FIRST_SERVICE, FRUIT_STATE_KEY);
        fruitMerkleMap = createMerkleMap(fruitLabel);
    }

    /** Sets up the "Fruit" virtual map, label, and metadata. */
    protected void setupFruitVirtualMap() {
        fruitVirtualLabel = StateMetadata.computeLabel(FIRST_SERVICE, FRUIT_STATE_KEY);
        fruitVirtualMap = createVirtualMap(
                fruitVirtualLabel,
                onDiskKeySerializerClassId(FRUIT_STATE_KEY),
                onDiskKeyClassId(FRUIT_STATE_KEY),
                STRING_CODEC,
                onDiskValueSerializerClassId(FRUIT_STATE_KEY),
                onDiskValueClassId(FRUIT_STATE_KEY),
                STRING_CODEC);
    }

    protected static long onDiskKeyClassId(String stateKey) {
        return onDiskKeyClassId(FIRST_SERVICE, stateKey);
    }

    protected static long onDiskKeyClassId(String serviceName, String stateKey) {
        return computeClassId(serviceName, stateKey, TEST_VERSION, ON_DISK_KEY_CLASS_ID_SUFFIX);
    }

    protected static long onDiskKeySerializerClassId(String stateKey) {
        return onDiskKeySerializerClassId(FIRST_SERVICE, stateKey);
    }

    protected static long onDiskKeySerializerClassId(String serviceName, String stateKey) {
        return computeClassId(serviceName, stateKey, TEST_VERSION, ON_DISK_KEY_SERIALIZER_CLASS_ID_SUFFIX);
    }

    protected static long onDiskValueClassId(String stateKey) {
        return onDiskValueClassId(FIRST_SERVICE, stateKey);
    }

    protected static long onDiskValueClassId(String serviceName, String stateKey) {
        return computeClassId(serviceName, stateKey, TEST_VERSION, ON_DISK_VALUE_CLASS_ID_SUFFIX);
    }

    protected static long onDiskValueSerializerClassId(String stateKey) {
        return onDiskValueSerializerClassId(FIRST_SERVICE, stateKey);
    }

    protected static long onDiskValueSerializerClassId(String serviceName, String stateKey) {
        return computeClassId(serviceName, stateKey, TEST_VERSION, ON_DISK_VALUE_SERIALIZER_CLASS_ID_SUFFIX);
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
                STRING_CODEC,
                AUSTRALIA);
    }

    protected void setupSteamQueue() {
        steamLabel = StateMetadata.computeLabel(FIRST_SERVICE, STEAM_STATE_KEY);
        steamQueue = new QueueNode<>(
                FIRST_SERVICE,
                STEAM_STATE_KEY,
                computeClassId(FIRST_SERVICE, STEAM_STATE_KEY, TEST_VERSION, QUEUE_NODE_CLASS_ID_SUFFIX),
                computeClassId(FIRST_SERVICE, STEAM_STATE_KEY, TEST_VERSION, SINGLETON_CLASS_ID_SUFFIX),
                STRING_CODEC);
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
    protected <K extends Comparable<K>, V> MerkleMap<InMemoryKey<K>, InMemoryValue<K, V>> createMerkleMap(
            String label) {
        final var map = new MerkleMap<InMemoryKey<K>, InMemoryValue<K, V>>();
        map.setLabel(label);
        return map;
    }

    /** Creates a new arbitrary virtual map with the given label, storageDir, and metadata */
    @SuppressWarnings("unchecked")
    protected VirtualMap<OnDiskKey<String>, OnDiskValue<String>> createVirtualMap(
            String label,
            long keySerializerClassId,
            long keyClassId,
            Codec<String> keyCodec,
            long valueSerializerClassId,
            long valueClassId,
            Codec<String> valueCodec) {
        final KeySerializer<OnDiskKey<String>> keySerializer =
                new OnDiskKeySerializer<>(keySerializerClassId, keyClassId, keyCodec);
        final ValueSerializer<OnDiskValue<String>> valueSerializer =
                new OnDiskValueSerializer<>(valueSerializerClassId, valueClassId, valueCodec);
        final MerkleDbTableConfig merkleDbTableConfig = new MerkleDbTableConfig((short) 1, DigestType.SHA_384, 100, 0);
        final var builder = new MerkleDbDataSourceBuilder(virtualDbPath, merkleDbTableConfig, CONFIGURATION);
        return new VirtualMap<>(label, keySerializer, valueSerializer, builder, CONFIGURATION);
    }

    /** A convenience method for adding a k/v pair to a merkle map */
    protected void add(
            MerkleMap<InMemoryKey<String>, InMemoryValue<String, String>> map,
            long inMemoryValueClassId,
            Codec<String> keyCodec,
            Codec<String> valueCodec,
            String key,
            String value) {
        final var k = new InMemoryKey<>(key);
        map.put(k, new InMemoryValue<>(inMemoryValueClassId, keyCodec, valueCodec, k, value));
    }

    /** A convenience method for adding a k/v pair to a virtual map */
    protected void add(
            VirtualMap<OnDiskKey<String>, OnDiskValue<String>> map,
            long onDiskKeyClassId,
            Codec<String> keyCodec,
            long onDiskValueClassId,
            Codec<String> valueCodec,
            String key,
            String value) {
        final var k = new OnDiskKey<>(onDiskKeyClassId, keyCodec, key);
        map.put(k, new OnDiskValue<>(onDiskValueClassId, valueCodec, value));
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
        // Restore to a fresh MerkleDb instance
        MerkleDb.resetDefaultInstancePath();
        final var byteInputStream = new ByteArrayInputStream(state);
        try (final var in = new MerkleDataInputStream(byteInputStream)) {
            return in.readMerkleTree(tempDir, 100);
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
        MerkleDb.resetDefaultInstancePath();

        if (fruitVirtualMap != null && fruitVirtualMap.getReservationCount() > -1) {
            fruitVirtualMap.release();
        }

        MerkleDbTestUtils.assertAllDatabasesClosed();
    }
}
