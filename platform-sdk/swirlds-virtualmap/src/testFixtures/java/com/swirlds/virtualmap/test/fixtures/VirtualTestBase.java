// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import static com.swirlds.virtualmap.test.fixtures.VirtualMapTestUtils.CONFIGURATION;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.MerkleLeaf;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.merkle.impl.PartialMerkleLeaf;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.config.VirtualMapConfig;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafRecord;
import com.swirlds.virtualmap.internal.cache.VirtualNodeCache;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

@SuppressWarnings("jol")
public class VirtualTestBase {
    protected static final Cryptography CRYPTO = CryptographyProvider.getInstance();

    // Keys that we will use repeatedly in these tests.
    protected static final TestKey A_KEY = new TestKey('A');
    protected static final TestKey B_KEY = new TestKey('B');
    protected static final TestKey C_KEY = new TestKey('C');
    protected static final TestKey D_KEY = new TestKey('D');
    protected static final TestKey E_KEY = new TestKey('E');
    protected static final TestKey F_KEY = new TestKey('F');
    protected static final TestKey G_KEY = new TestKey('G');

    protected static final TestValue APPLE = (TestValue) new TestValue("Apple").asReadOnly();
    protected static final TestValue BANANA = (TestValue) new TestValue("Banana").asReadOnly();
    protected static final TestValue CHERRY = (TestValue) new TestValue("Cherry").asReadOnly();
    protected static final TestValue DATE = (TestValue) new TestValue("Date").asReadOnly();
    protected static final TestValue EGGPLANT = (TestValue) new TestValue("Eggplant").asReadOnly();
    protected static final TestValue FIG = (TestValue) new TestValue("Fig").asReadOnly();
    protected static final TestValue GRAPE = (TestValue) new TestValue("Grape").asReadOnly();

    protected static final TestValue AARDVARK = (TestValue) new TestValue("Aardvark").asReadOnly();
    protected static final TestValue BEAR = (TestValue) new TestValue("Bear").asReadOnly();
    protected static final TestValue CUTTLEFISH = (TestValue) new TestValue("Cuttlefish").asReadOnly();
    protected static final TestValue DOG = (TestValue) new TestValue("Dog").asReadOnly();
    protected static final TestValue EMU = (TestValue) new TestValue("Emu").asReadOnly();
    protected static final TestValue FOX = (TestValue) new TestValue("Fox").asReadOnly();
    protected static final TestValue GOOSE = (TestValue) new TestValue("Goose").asReadOnly();

    protected static final TestValue ASTRONAUT = (TestValue) new TestValue("Astronaut").asReadOnly();
    protected static final TestValue BLASTOFF = (TestValue) new TestValue("Blastoff").asReadOnly();
    protected static final TestValue COMET = (TestValue) new TestValue("Comet").asReadOnly();
    protected static final TestValue DRACO = (TestValue) new TestValue("Draco").asReadOnly();
    protected static final TestValue EXOPLANET = (TestValue) new TestValue("Exoplanet").asReadOnly();
    protected static final TestValue FORCE = (TestValue) new TestValue("Force").asReadOnly();
    protected static final TestValue GRAVITY = (TestValue) new TestValue("Gravity").asReadOnly();

    protected static final TestValue ASTRONOMY = (TestValue) new TestValue("Astronomy").asReadOnly();
    protected static final TestValue BIOLOGY = (TestValue) new TestValue("Biology").asReadOnly();
    protected static final TestValue CHEMISTRY = (TestValue) new TestValue("Chemistry").asReadOnly();
    protected static final TestValue DISCIPLINE = (TestValue) new TestValue("Discipline").asReadOnly();
    protected static final TestValue ECOLOGY = (TestValue) new TestValue("Ecology").asReadOnly();
    protected static final TestValue FIELDS = (TestValue) new TestValue("Fields").asReadOnly();
    protected static final TestValue GEOMETRY = (TestValue) new TestValue("Geometry").asReadOnly();

    protected static final TestValue AUSTRALIA = (TestValue) new TestValue("Australia").asReadOnly();
    protected static final TestValue BRAZIL = (TestValue) new TestValue("Brazil").asReadOnly();
    protected static final TestValue CHAD = (TestValue) new TestValue("Chad").asReadOnly();
    protected static final TestValue DENMARK = (TestValue) new TestValue("Denmark").asReadOnly();
    protected static final TestValue ESTONIA = (TestValue) new TestValue("Estonia").asReadOnly();
    protected static final TestValue FRANCE = (TestValue) new TestValue("France").asReadOnly();
    protected static final TestValue GHANA = (TestValue) new TestValue("Ghana").asReadOnly();

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

    protected List<VirtualNodeCache<TestKey, TestValue>> rounds;
    protected VirtualNodeCache<TestKey, TestValue> cache;
    private VirtualNodeCache<TestKey, TestValue> lastCache;

    private VirtualHashRecord rootInternal;
    private VirtualHashRecord leftInternal;
    private VirtualHashRecord rightInternal;
    private VirtualHashRecord leftLeftInternal;
    private VirtualHashRecord leftRightInternal;
    private VirtualHashRecord rightLeftInternal;
    private VirtualLeafRecord<TestKey, TestValue> lastALeaf;
    private VirtualLeafRecord<TestKey, TestValue> lastBLeaf;
    private VirtualLeafRecord<TestKey, TestValue> lastCLeaf;
    private VirtualLeafRecord<TestKey, TestValue> lastDLeaf;
    private VirtualLeafRecord<TestKey, TestValue> lastELeaf;
    private VirtualLeafRecord<TestKey, TestValue> lastFLeaf;
    private VirtualLeafRecord<TestKey, TestValue> lastGLeaf;

    @BeforeAll
    static void globalSetup() throws Exception {
        // Ensure VirtualNodeCache.release() returns clean
        System.setProperty("syncCleaningPool", "true");
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("org.hiero");
        registry.registerConstructables("com.swirlds.virtualmap");
        registry.registerConstructables("com.swirlds.virtualmap.test.fixtures");
        registry.registerConstructable(new ClassConstructorPair(TestKey.class, () -> new TestKey(0L)));
        registry.registerConstructable(new ClassConstructorPair(TestValue.class, () -> new TestValue("")));
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
        cache = new VirtualNodeCache<>(CONFIGURATION.getConfigData(VirtualMapConfig.class));
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

    protected VirtualLeafRecord<TestKey, TestValue> leaf(long path, long key, long value) {
        return new VirtualLeafRecord<>(path, new TestKey(key), new TestValue(value));
    }

    protected VirtualLeafRecord<TestKey, TestValue> appleLeaf(long path) {
        lastALeaf =
                lastALeaf == null ? new VirtualLeafRecord<>(path, A_KEY, APPLE) : copyWithPath(lastALeaf, APPLE, path);
        return lastALeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> bananaLeaf(long path) {
        lastBLeaf = lastBLeaf == null
                ? new VirtualLeafRecord<>(path, B_KEY, BANANA)
                : copyWithPath(lastBLeaf, BANANA, path);
        return lastBLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> cherryLeaf(long path) {
        lastCLeaf = lastCLeaf == null
                ? new VirtualLeafRecord<>(path, C_KEY, CHERRY)
                : copyWithPath(lastCLeaf, CHERRY, path);
        return lastCLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> dateLeaf(long path) {
        lastDLeaf =
                lastDLeaf == null ? new VirtualLeafRecord<>(path, D_KEY, DATE) : copyWithPath(lastDLeaf, DATE, path);
        return lastDLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> eggplantLeaf(long path) {
        lastELeaf = lastELeaf == null
                ? new VirtualLeafRecord<>(path, E_KEY, EGGPLANT)
                : copyWithPath(lastELeaf, EGGPLANT, path);
        return lastELeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> figLeaf(long path) {
        lastFLeaf = lastFLeaf == null ? new VirtualLeafRecord<>(path, F_KEY, FIG) : copyWithPath(lastFLeaf, FIG, path);
        return lastFLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> grapeLeaf(long path) {
        lastGLeaf =
                lastGLeaf == null ? new VirtualLeafRecord<>(path, G_KEY, GRAPE) : copyWithPath(lastGLeaf, GRAPE, path);
        return lastGLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> aardvarkLeaf(long path) {
        lastALeaf = lastALeaf == null
                ? new VirtualLeafRecord<>(path, A_KEY, AARDVARK)
                : copyWithPath(lastALeaf, AARDVARK, path);
        return lastALeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> bearLeaf(long path) {
        lastBLeaf =
                lastBLeaf == null ? new VirtualLeafRecord<>(path, B_KEY, BEAR) : copyWithPath(lastBLeaf, BEAR, path);
        return lastBLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> cuttlefishLeaf(long path) {
        lastCLeaf = lastCLeaf == null
                ? new VirtualLeafRecord<>(path, C_KEY, CUTTLEFISH)
                : copyWithPath(lastCLeaf, CUTTLEFISH, path);
        return lastCLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> dogLeaf(long path) {
        lastDLeaf = lastDLeaf == null ? new VirtualLeafRecord<>(path, D_KEY, DOG) : copyWithPath(lastDLeaf, DOG, path);
        return lastDLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> emuLeaf(long path) {
        lastELeaf = lastELeaf == null ? new VirtualLeafRecord<>(path, E_KEY, EMU) : copyWithPath(lastELeaf, EMU, path);
        return lastELeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> foxLeaf(long path) {
        lastFLeaf = lastFLeaf == null ? new VirtualLeafRecord<>(path, F_KEY, FOX) : copyWithPath(lastFLeaf, FOX, path);
        return lastFLeaf;
    }

    protected VirtualLeafRecord<TestKey, TestValue> gooseLeaf(long path) {
        lastGLeaf =
                lastGLeaf == null ? new VirtualLeafRecord<>(path, G_KEY, GOOSE) : copyWithPath(lastGLeaf, GOOSE, path);
        return lastGLeaf;
    }

    protected VirtualHashRecord hash(VirtualLeafRecord<TestKey, TestValue> rec) {
        return new VirtualHashRecord(rec.getPath(), CRYPTO.digestSync(rec));
    }

    private VirtualLeafRecord<TestKey, TestValue> copyWithPath(
            VirtualLeafRecord<TestKey, TestValue> leaf, TestValue value, long path) {
        return new VirtualLeafRecord<>(path, leaf.getKey(), value);
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
