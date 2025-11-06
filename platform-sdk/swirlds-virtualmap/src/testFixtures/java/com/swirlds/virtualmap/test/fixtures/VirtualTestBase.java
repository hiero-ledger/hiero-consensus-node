// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;

import com.hedera.pbj.runtime.hashing.WritableMessageDigest;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyException;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.crypto.Hash;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

@SuppressWarnings("jol")
public class VirtualTestBase {

    protected static final Cryptography CRYPTO = CryptographyProvider.getInstance();

    // Keys that we will use repeatedly in these tests.
    protected static final Bytes A_KEY = TestKey.charToKey('A');
    protected static final Bytes B_KEY = TestKey.charToKey('B');
    protected static final Bytes C_KEY = TestKey.charToKey('C');
    protected static final Bytes D_KEY = TestKey.charToKey('D');
    protected static final Bytes E_KEY = TestKey.charToKey('E');
    protected static final Bytes F_KEY = TestKey.charToKey('F');
    protected static final Bytes G_KEY = TestKey.charToKey('G');

    protected static final TestValue APPLE = new TestValue("Apple");
    protected static final TestValue BANANA = new TestValue("Banana");
    protected static final TestValue CHERRY = new TestValue("Cherry");
    protected static final TestValue DATE = new TestValue("Date");
    protected static final TestValue EGGPLANT = new TestValue("Eggplant");
    protected static final TestValue FIG = new TestValue("Fig");
    protected static final TestValue GRAPE = new TestValue("Grape");

    protected static final TestValue AARDVARK = new TestValue("Aardvark");
    protected static final TestValue BEAR = new TestValue("Bear");
    protected static final TestValue CUTTLEFISH = new TestValue("Cuttlefish");
    protected static final TestValue DOG = new TestValue("Dog");
    protected static final TestValue EMU = new TestValue("Emu");
    protected static final TestValue FOX = new TestValue("Fox");
    protected static final TestValue GOOSE = new TestValue("Goose");

    protected static final TestValue ASTRONAUT = new TestValue("Astronaut");
    protected static final TestValue BLASTOFF = new TestValue("Blastoff");
    protected static final TestValue COMET = new TestValue("Comet");
    protected static final TestValue DRACO = new TestValue("Draco");
    protected static final TestValue EXOPLANET = new TestValue("Exoplanet");
    protected static final TestValue FORCE = new TestValue("Force");
    protected static final TestValue GRAVITY = new TestValue("Gravity");

    protected static final TestValue ASTRONOMY = new TestValue("Astronomy");
    protected static final TestValue BIOLOGY = new TestValue("Biology");
    protected static final TestValue CHEMISTRY = new TestValue("Chemistry");
    protected static final TestValue DISCIPLINE = new TestValue("Discipline");
    protected static final TestValue ECOLOGY = new TestValue("Ecology");
    protected static final TestValue FIELDS = new TestValue("Fields");
    protected static final TestValue GEOMETRY = new TestValue("Geometry");

    protected static final TestValue AUSTRALIA = new TestValue("Australia");
    protected static final TestValue BRAZIL = new TestValue("Brazil");
    protected static final TestValue CHAD = new TestValue("Chad");
    protected static final TestValue DENMARK = new TestValue("Denmark");
    protected static final TestValue ESTONIA = new TestValue("Estonia");
    protected static final TestValue FRANCE = new TestValue("France");
    protected static final TestValue GHANA = new TestValue("Ghana");

    protected static final long D_PATH = 6;
    protected static final long A_PATH = 7;
    protected static final long E_PATH = 8;
    protected static final long C_PATH = 9;
    protected static final long F_PATH = 10;
    protected static final long B_PATH = 11;
    protected static final long G_PATH = 12;
    protected static final long ROOT_PATH = 0;
    protected static final long LEFT_PATH = 1;
    protected static final long RIGHT_PATH = 2;
    protected static final long LEFT_LEFT_PATH = 3;
    protected static final long LEFT_RIGHT_PATH = 4;
    protected static final long RIGHT_LEFT_PATH = 5;

    protected List<VirtualNodeCache> rounds;
    protected VirtualNodeCache cache;
    private VirtualNodeCache lastCache;

    private VirtualHashRecord rootInternal;
    private VirtualHashRecord leftInternal;
    private VirtualHashRecord rightInternal;
    private VirtualHashRecord leftLeftInternal;
    private VirtualHashRecord leftRightInternal;
    private VirtualHashRecord rightLeftInternal;
    private VirtualLeafBytes<TestValue> lastALeaf;
    private VirtualLeafBytes<TestValue> lastBLeaf;
    private VirtualLeafBytes<TestValue> lastCLeaf;
    private VirtualLeafBytes<TestValue> lastDLeaf;
    private VirtualLeafBytes<TestValue> lastELeaf;
    private VirtualLeafBytes<TestValue> lastFLeaf;
    private VirtualLeafBytes<TestValue> lastGLeaf;

    @BeforeAll
    static void globalSetup() throws Exception {
        // Ensure VirtualNodeCache.release() returns clean
        System.setProperty("syncCleaningPool", "true");
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("org.hiero");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructables("com.swirlds.virtualmap.test.fixtures");
        registry.registerConstructable(new ClassConstructorPair(TestInternal.class, TestInternal::new));
        registry.registerConstructable(new ClassConstructorPair(TestLeaf.class, TestLeaf::new));
        registry.registerConstructable(new ClassConstructorPair(VirtualMap.class, () -> new VirtualMap(CONFIGURATION)));
        registry.registerConstructable(new ClassConstructorPair(
                VirtualNodeCache.class,
                () -> new VirtualNodeCache(CONFIGURATION.getConfigData(VirtualMapConfig.class))));
    }

    @BeforeEach
    public void setup() {
        rounds = new ArrayList<>();
        cache = new VirtualNodeCache(CONFIGURATION.getConfigData(VirtualMapConfig.class));
        rounds.add(cache);
        lastCache = null;
    }

    // NOTE: If nextRound automatically causes hashing, some tests in VirtualNodeCacheTest will fail or be invalid.
    protected void nextRound() {
        lastCache = cache;
        cache = cache.copy();
        rounds.add(cache);
    }

    protected VirtualHashRecord rootInternal() {
        rootInternal = rootInternal == null ? new VirtualHashRecord(ROOT_PATH) : copy(rootInternal);
        return rootInternal;
    }

    protected VirtualHashRecord leftInternal() {
        leftInternal = leftInternal == null ? new VirtualHashRecord(LEFT_PATH) : copy(leftInternal);
        return leftInternal;
    }

    protected VirtualHashRecord rightInternal() {
        rightInternal = rightInternal == null ? new VirtualHashRecord(RIGHT_PATH) : copy(rightInternal);
        return rightInternal;
    }

    protected VirtualHashRecord leftLeftInternal() {
        leftLeftInternal = leftLeftInternal == null ? new VirtualHashRecord(LEFT_LEFT_PATH) : copy(leftLeftInternal);
        return leftLeftInternal;
    }

    protected VirtualHashRecord leftRightInternal() {
        leftRightInternal =
                leftRightInternal == null ? new VirtualHashRecord(LEFT_RIGHT_PATH) : copy(leftRightInternal);
        return leftRightInternal;
    }

    protected VirtualHashRecord rightLeftInternal() {
        rightLeftInternal =
                rightLeftInternal == null ? new VirtualHashRecord(RIGHT_LEFT_PATH) : copy(rightLeftInternal);
        return rightLeftInternal;
    }

    protected VirtualLeafBytes<TestValue> leaf(long path, long key, long value) {
        return new VirtualLeafBytes<>(path, TestKey.longToKey(key), new TestValue(value), TestValueCodec.INSTANCE);
    }

    protected VirtualLeafBytes<TestValue> appleLeaf(long path) {
        lastALeaf = lastALeaf == null
                ? new VirtualLeafBytes<>(path, A_KEY, APPLE, TestValueCodec.INSTANCE)
                : copyWithPath(lastALeaf, APPLE, path);
        return lastALeaf;
    }

    protected VirtualLeafBytes<TestValue> bananaLeaf(long path) {
        lastBLeaf = lastBLeaf == null
                ? new VirtualLeafBytes<>(path, B_KEY, BANANA, TestValueCodec.INSTANCE)
                : copyWithPath(lastBLeaf, BANANA, path);
        return lastBLeaf;
    }

    protected VirtualLeafBytes<TestValue> cherryLeaf(long path) {
        lastCLeaf = lastCLeaf == null
                ? new VirtualLeafBytes<>(path, C_KEY, CHERRY, TestValueCodec.INSTANCE)
                : copyWithPath(lastCLeaf, CHERRY, path);
        return lastCLeaf;
    }

    protected VirtualLeafBytes<TestValue> dateLeaf(long path) {
        lastDLeaf = lastDLeaf == null
                ? new VirtualLeafBytes<>(path, D_KEY, DATE, TestValueCodec.INSTANCE)
                : copyWithPath(lastDLeaf, DATE, path);
        return lastDLeaf;
    }

    protected VirtualLeafBytes<TestValue> eggplantLeaf(long path) {
        lastELeaf = lastELeaf == null
                ? new VirtualLeafBytes<>(path, E_KEY, EGGPLANT, TestValueCodec.INSTANCE)
                : copyWithPath(lastELeaf, EGGPLANT, path);
        return lastELeaf;
    }

    protected VirtualLeafBytes<TestValue> figLeaf(long path) {
        lastFLeaf = lastFLeaf == null
                ? new VirtualLeafBytes<>(path, F_KEY, FIG, TestValueCodec.INSTANCE)
                : copyWithPath(lastFLeaf, FIG, path);
        return lastFLeaf;
    }

    protected VirtualLeafBytes<TestValue> grapeLeaf(long path) {
        lastGLeaf = lastGLeaf == null
                ? new VirtualLeafBytes<>(path, G_KEY, GRAPE, TestValueCodec.INSTANCE)
                : copyWithPath(lastGLeaf, GRAPE, path);
        return lastGLeaf;
    }

    protected VirtualLeafBytes<TestValue> aardvarkLeaf(long path) {
        lastALeaf = lastALeaf == null
                ? new VirtualLeafBytes<>(path, A_KEY, AARDVARK, TestValueCodec.INSTANCE)
                : copyWithPath(lastALeaf, AARDVARK, path);
        return lastALeaf;
    }

    protected VirtualLeafBytes<TestValue> bearLeaf(long path) {
        lastBLeaf = lastBLeaf == null
                ? new VirtualLeafBytes<>(path, B_KEY, BEAR, TestValueCodec.INSTANCE)
                : copyWithPath(lastBLeaf, BEAR, path);
        return lastBLeaf;
    }

    protected VirtualLeafBytes<TestValue> cuttlefishLeaf(long path) {
        lastCLeaf = lastCLeaf == null
                ? new VirtualLeafBytes<>(path, C_KEY, CUTTLEFISH, TestValueCodec.INSTANCE)
                : copyWithPath(lastCLeaf, CUTTLEFISH, path);
        return lastCLeaf;
    }

    protected VirtualLeafBytes<TestValue> dogLeaf(long path) {
        lastDLeaf = lastDLeaf == null
                ? new VirtualLeafBytes<>(path, D_KEY, DOG, TestValueCodec.INSTANCE)
                : copyWithPath(lastDLeaf, DOG, path);
        return lastDLeaf;
    }

    protected VirtualLeafBytes<TestValue> emuLeaf(long path) {
        lastELeaf = lastELeaf == null
                ? new VirtualLeafBytes<>(path, E_KEY, EMU, TestValueCodec.INSTANCE)
                : copyWithPath(lastELeaf, EMU, path);
        return lastELeaf;
    }

    protected VirtualLeafBytes<TestValue> foxLeaf(long path) {
        lastFLeaf = lastFLeaf == null
                ? new VirtualLeafBytes<>(path, F_KEY, FOX, TestValueCodec.INSTANCE)
                : copyWithPath(lastFLeaf, FOX, path);
        return lastFLeaf;
    }

    protected VirtualLeafBytes<TestValue> gooseLeaf(long path) {
        lastGLeaf = lastGLeaf == null
                ? new VirtualLeafBytes<>(path, G_KEY, GOOSE, TestValueCodec.INSTANCE)
                : copyWithPath(lastGLeaf, GOOSE, path);
        return lastGLeaf;
    }

    protected static Hash hash(VirtualLeafBytes<TestValue> rec) {
        try {
            final MessageDigest md = MessageDigest.getInstance(Cryptography.DEFAULT_DIGEST_TYPE.algorithmName());
            final WritableMessageDigest wmd = new WritableMessageDigest(md);
            rec.writeToForHashing(wmd);
            return new Hash(wmd.digest(), Cryptography.DEFAULT_DIGEST_TYPE);
        } catch (final NoSuchAlgorithmException e) {
            throw new CryptographyException(e);
        }
    }

    protected VirtualHashRecord hashRecord(VirtualLeafBytes<TestValue> rec) {
        return new VirtualHashRecord(rec.path(), hash(rec));
    }

    private VirtualLeafBytes<TestValue> copyWithPath(VirtualLeafBytes<TestValue> leaf, TestValue value, long path) {
        return new VirtualLeafBytes<>(path, leaf.keyBytes(), value, TestValueCodec.INSTANCE);
    }

    private VirtualHashRecord copy(VirtualHashRecord rec) {
        return new VirtualHashRecord(rec.path(), rec.hash());
    }

    public static final class TestInternal extends PartialBinaryMerkleInternal implements MerkleInternal {
        private String label;

        public TestInternal() {
            // For serialization
        }

        public TestInternal(String label) {
            this.label = label;
        }

        private TestInternal(TestInternal other) {
            super(other);
            this.label = other.label;
        }

        @Override
        public long getClassId() {
            return 1234;
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public TestInternal copy() {
            return new TestInternal(this);
        }

        @Override
        public String toString() {
            return "TestInternal{" + "label='" + label + '\'' + '}';
        }
    }

    public static final class TestLeaf extends PartialMerkleLeaf implements MerkleLeaf {
        private String value;

        // For serialization engine
        public TestLeaf() {}

        public TestLeaf(String value) {
            this.value = value;
        }

        private TestLeaf(TestLeaf other) {
            super(other);
            this.value = other.value;
        }

        @Override
        public long getClassId() {
            return 2345;
        }

        @Override
        public int getVersion() {
            return 1;
        }

        @Override
        public void serialize(SerializableDataOutputStream out) throws IOException {
            out.writeUTF(value);
        }

        @Override
        public void deserialize(SerializableDataInputStream in, int version) throws IOException {
            this.value = in.readUTF();
        }

        @Override
        public TestLeaf copy() {
            return new TestLeaf(this);
        }

        @Override
        public String toString() {
            return "TestLeaf{" + "value='" + value + '\'' + '}';
        }
    }
}
