// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.crypto.Cryptography;
import org.hiero.base.crypto.CryptographyProvider;
import org.junit.jupiter.api.BeforeAll;

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
    protected static final long RIGHT_RIGHT_PATH = 6;

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

    private VirtualLeafBytes<TestValue> copyWithPath(VirtualLeafBytes<TestValue> leaf, TestValue value, long path) {
        return new VirtualLeafBytes<>(path, leaf.keyBytes(), value, TestValueCodec.INSTANCE);
    }
}
