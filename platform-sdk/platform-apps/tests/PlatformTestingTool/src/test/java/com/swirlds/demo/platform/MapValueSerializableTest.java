// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord.DEFAULT_EXPIRATION_TIME;
import static com.swirlds.platform.test.fixtures.config.ConfigUtils.CONFIGURATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.common.merkle.utility.Keyed;
import com.swirlds.common.merkle.utility.MerkleLong;
import com.swirlds.common.test.fixtures.merkle.TestMerkleCryptoFactory;
import com.swirlds.demo.merkle.map.FCMFamily;
import com.swirlds.demo.merkle.map.MapValueData;
import com.swirlds.demo.merkle.map.MapValueFCQ;
import com.swirlds.demo.merkle.map.internal.DummyExpectedFCMFamily;
import com.swirlds.demo.merkle.map.internal.ExpectedFCMFamily;
import com.swirlds.demo.platform.expiration.ExpirationRecordEntry;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.lifecycle.ExpectedValue;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.test.fixtures.map.pta.MapValue;
import com.swirlds.merkle.test.fixtures.map.pta.TransactionRecord;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.crypto.Hash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapValueSerializableTest {

    static final int FCQ_TTL = 120;
    static Random random = new Random();
    private static MerkleCryptography cryptography;

    /**
     * Temporary directory provided by JUnit
     */
    @TempDir
    Path testDirectory;

    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructables("com.swirlds");
        registry.registerConstructables("org.hiero");
        cryptography = TestMerkleCryptoFactory.getInstance();
    }

    @AfterAll
    public static void shutdown() {}

    @Test
    void mapValueSerializable() {
        final Random random = new Random();
        long randomValue = random.nextLong() % 100000L;
        MapValueData mapValueData =
                new MapValueData(randomValue, randomValue + 1, randomValue + 2, false, random.nextLong());

        try {
            final ByteArrayOutputStream streamReal = new ByteArrayOutputStream();
            final ObjectOutputStream oos = new ObjectOutputStream(streamReal);

            oos.writeObject(mapValueData);
            oos.flush();
            oos.close();

            final byte[] realBytes = streamReal.toByteArray();

            final ByteArrayInputStream inStream = new ByteArrayInputStream(realBytes);
            final ObjectInputStream is = new ObjectInputStream(inStream);
            MapValueData readBackValue = (MapValueData) is.readObject();

            assertEquals(mapValueData, readBackValue);

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    private MapValueFCQ<TransactionRecord> addRecords(
            MapKey mapKey,
            MapValueFCQ<TransactionRecord> value,
            final int balance,
            byte[] content,
            BlockingQueue<ExpirationRecordEntry> expirationQueue,
            HashSet<MapKey> accountsWithExpiringRecords) {
        List<TransactionRecord> records = new ArrayList<>();
        for (int i = 1; i < 10000; i++) {
            final TransactionRecord transactionRecord = new TransactionRecord(i, balance, content, getExpirationTime());
            records.add(transactionRecord);
        }
        return value.addRecords(balance, records, mapKey, expirationQueue, accountsWithExpiringRecords);
    }

    private TransactionRecord randomRecord(final long idx) {
        final byte[] content = new byte[100];
        random.nextBytes(content);
        return new TransactionRecord(idx, random.nextInt(), content, getExpirationTime());
    }

    private void insertIntoFCQMap(
            final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMap,
            final int size,
            final BlockingQueue<ExpirationRecordEntry> expirationQueue,
            final HashSet<MapKey> accountsWithExpiringRecords) {
        // create value
        for (int i = 0; i < size; i++) {
            byte[] content = new byte[100];
            random.nextBytes(content);
            final int balance = 100 * i;
            final MapKey key = new MapKey(0, 0, i);
            MapValueFCQ<TransactionRecord> value = MapValueFCQ.newBuilder()
                    .setIndex(i)
                    .setBalance(balance)
                    .setContent(content)
                    .setMapKey(key)
                    .setExpirationQueue(expirationQueue)
                    .setExpirationTime(getExpirationTime())
                    .setAccountsWithExpiringRecords(accountsWithExpiringRecords)
                    .build();
            value = addRecords(key, value, balance, content, expirationQueue, accountsWithExpiringRecords);
            // value = value.addRecord(balance, randomRecord(0));
            fcqMap.put(key, value);
        }
    }

    @Test
    void mapValueFCQSerializeAndDeserialize() {
        final FCMFamily fcmFamily = new FCMFamily(true);
        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMap = fcmFamily.getAccountFCQMap();
        final BlockingQueue<ExpirationRecordEntry> expirationQueue = new PriorityBlockingQueue<>();
        final HashSet<MapKey> accountsWithExpiringRecords = new HashSet<>();

        final int size = 10;
        insertIntoFCQMap(fcqMap, size, expirationQueue, accountsWithExpiringRecords);

        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMap01 = fcqMap.copy();
        validateSerializeAndDeserialize(fcqMap);

        // update
        for (int i = 0; i < 50000; i++) {
            final MapKey key = new MapKey(0, 0, i % size);
            byte[] content = new byte[100];
            random.nextBytes(content);
            final MapValueFCQ<TransactionRecord> value = fcqMap01.getForModify(key);
            final TransactionRecord transactionRecord = randomRecord(random.nextInt());
            MapValueFCQ<TransactionRecord> newValue = value.addRecord(
                    random.nextInt(), transactionRecord, key, expirationQueue, accountsWithExpiringRecords);
            fcqMap01.replace(key, newValue);
        }

        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMap02 = fcqMap01.copy();
        validateSerializeAndDeserialize(fcqMap01);

        // transfer
        for (int i = 0; i < 5000; i++) {
            final MapKey fromKey = new MapKey(0, 0, i % size);
            final MapKey toKey = new MapKey(0, 0, (i + 1) % size);
            byte[] content = new byte[100];
            random.nextBytes(content);
            final int amount = i;
            final MapValueFCQ<TransactionRecord> fromValue = fcqMap02.getForModify(fromKey);
            final MapValueFCQ<TransactionRecord> newFromValue = fromValue.transferFrom(
                    amount, content, fromKey, getExpirationTime(), expirationQueue, accountsWithExpiringRecords);
            final MapValueFCQ<TransactionRecord> toValue = fcqMap02.getForModify(toKey);
            final MapValueFCQ<TransactionRecord> newToValue = toValue.transferTo(
                    amount, content, toKey, getExpirationTime(), expirationQueue, accountsWithExpiringRecords);
            fcqMap02.replace(fromKey, newFromValue);
            fcqMap02.replace(toKey, newToValue);
        }

        validateSerializeAndDeserialize(fcqMap02);
    }

    @Test
    void mapValueFCQCompareGFMtoGet() { // getForModify vs get version
        final FCMFamily fcmFamily = new FCMFamily(true);
        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMapGFM = fcmFamily.getAccountFCQMap();
        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMapGet = fcmFamily.getAccountFCQMap();

        final int size = 10;
        insertIntoFCQMap(fcqMapGFM, size, null, null);
        insertIntoFCQMap(fcqMapGet, size, null, null);

        // assert that the maps have the same content
        compareTwoFCMs(fcqMapGet, fcqMapGFM);

        // updates
        for (int i = 0; i < 50000; i++) {
            final MapKey key = new MapKey(0, 0, i % size);
            byte[] content = new byte[100];
            random.nextBytes(content);
            final int randomRecordID = random.nextInt();

            // get update pattern (to deprecate)
            final MapValueFCQ<TransactionRecord> value = fcqMapGet.get(key);
            final TransactionRecord transactionRecordGet = randomRecord(randomRecordID);
            MapValueFCQ<TransactionRecord> newValueGet =
                    value.addRecord(randomRecordID, transactionRecordGet, null, null, null);
            fcqMapGet.replace(key, newValueGet);

            // gfm update pattern
            final MapValueFCQ<TransactionRecord> valueGFM = fcqMapGFM.getForModify(key);
            final TransactionRecord transactionRecordGFM = randomRecord(randomRecordID);
            valueGFM.getRecords().add(transactionRecordGFM); // <-- combine these two or three into just modifying a
            valueGFM.setBalance(new MerkleLong(randomRecordID)); //  balance and adding a record - appendRecord()
            fcqMapGFM.replace(key, valueGFM);
        }

        // assert that the maps have the same content
        compareTwoFCMs(fcqMapGet, fcqMapGFM);

        // transfers
        for (int i = 0; i < 5000; i++) {
            final MapKey fromKey = new MapKey(0, 0, i % size);
            final MapKey toKey = new MapKey(0, 0, (i + 1) % size);
            byte[] content = new byte[100];
            random.nextBytes(content);
            final int amount = i;

            // get from (to deprecate)
            final MapValueFCQ<TransactionRecord> fromValueGet = fcqMapGet.get(fromKey);
            final MapValueFCQ<TransactionRecord> newFromValue = fromValueGet.transferFrom(
                    amount, content, fromKey, Instant.now().toEpochMilli() + DEFAULT_EXPIRATION_TIME, null, null);
            fcqMapGet.replace(fromKey, newFromValue);
            // get to (to deprecate)
            final MapValueFCQ<TransactionRecord> toValueGet = fcqMapGet.get(toKey);
            final MapValueFCQ<TransactionRecord> newToValue = toValueGet.transferTo(
                    amount, content, toKey, Instant.now().toEpochMilli() + DEFAULT_EXPIRATION_TIME, null, null);
            fcqMapGet.replace(toKey, newToValue);

            // explicit transfer added to existing leaf -- no method calls until we're sure it works
            // modifies the fromValue and toValue balance in place without using transferFrom, transferTo
            // gfm from
            final MapValueFCQ<TransactionRecord> fromValueGFM = fcqMapGFM.getForModify(fromKey);
            final long newFromBalance = fromValueGFM.getBalance().getValue() - amount;
            final int fromRecordCount = fromValueGFM.getRecordsSize();
            TransactionRecord newFromTransaction = new TransactionRecord(
                    fromRecordCount, newFromBalance, content, Instant.now().toEpochMilli() + DEFAULT_EXPIRATION_TIME);
            fromValueGFM.getRecords().add(newFromTransaction); // <-- combine these two or three into just modifying a
            fromValueGFM.setBalance(new MerkleLong(newFromBalance)); //  balance and adding a record - appendRecord()
            fcqMapGFM.replace(fromKey, fromValueGFM);
            // gfm to
            final MapValueFCQ<TransactionRecord> toValueGFM = fcqMapGFM.getForModify(toKey);
            final long newToBalance = toValueGFM.getBalance().getValue() + amount;
            final int toRecordCount = toValueGFM.getRecordsSize();
            TransactionRecord newToTransaction = new TransactionRecord(
                    toRecordCount, newToBalance, content, Instant.now().toEpochMilli() + DEFAULT_EXPIRATION_TIME);
            toValueGFM.getRecords().add(newToTransaction);
            toValueGFM.setBalance(new MerkleLong(newToBalance));
            fcqMapGFM.replace(toKey, toValueGFM);
        }
        // assert that the maps have the same content
        compareTwoFCMs(fcqMapGet, fcqMapGFM);
    }

    @Test
    void mapValueFCQSerializeAndDeserializeZeroRecords() {
        final FCMFamily fcmFamily = new FCMFamily(true);
        final MerkleMap<MapKey, MapValueFCQ<TransactionRecord>> fcqMap = fcmFamily.getAccountFCQMap();

        final int size = 10;
        for (int i = 0; i < size; i++) {
            byte[] content = new byte[100];
            random.nextBytes(content);
            final int balance = 100 * i;
            final MapKey key = new MapKey(0, 0, i);
            MapValueFCQ<TransactionRecord> value = MapValueFCQ.newBuilder()
                    .setIndex(i)
                    .setBalance(balance)
                    .setContent(content)
                    .setMapKey(key)
                    .setExpirationQueue(null)
                    .setExpirationTime(DEFAULT_EXPIRATION_TIME)
                    .setAccountsWithExpiringRecords(null)
                    .build();
            // add zero records
            value = value.deleteFirst();
            fcqMap.put(key, value);
            assertEquals(fcqMap.get(key).getRecordsSize(), 0);
        }
        validateSerializeAndDeserialize(fcqMap);
    }

    private <T extends MerkleNode & Keyed<MapKey>> void validateSerializeAndDeserialize(
            final MerkleMap<MapKey, T> map) {
        try {

            final ByteArrayOutputStream outStream = new ByteArrayOutputStream();
            final MerkleDataOutputStream outputStream = new MerkleDataOutputStream(outStream);
            cryptography.digestTreeSync(map);
            outputStream.writeMerkleTree(testDirectory, map);
            outputStream.flush();

            final ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
            final MerkleDataInputStream inputStream = new MerkleDataInputStream(inStream);
            final MerkleMap<MapKey, T> deserializedMap =
                    inputStream.readMerkleTree(CONFIGURATION, testDirectory, Integer.MAX_VALUE);
            cryptography.digestTreeSync(deserializedMap);

            assertEquals(map, deserializedMap);
        } catch (IOException ex) {
            // when deserializing check if this error is not thrown
            assertFalse(ex.getMessage().contains("didn't consume the input stream correctly"));
            ex.printStackTrace();
        }
    }

    private <T extends MapValue & Keyed<MapKey>> void compareTwoFCMs(
            final MerkleMap<MapKey, T> map1, final MerkleMap<MapKey, T> map2) {

        ArrayList<T> children = new ArrayList<>();
        // traverse first one and build a list
        int count = 0;
        for (Map.Entry<MapKey, T> item : map1.entrySet()) {
            T curr = item.getValue();
            children.add(curr);
            count++;
        }
        // traverse second one and compare the list entries
        int index = 0;
        for (Map.Entry<MapKey, T> item : map2.entrySet()) {
            T curr2 = item.getValue();
            T curr1 = children.get(index);
            assertEquals(curr1, curr2);
            try {
                assertEquals(curr1.calculateHash(), curr2.calculateHash());
            } catch (IOException ex) {
                System.out.println("got IOException when calculateHash");
            }
            index++;
        }
        assertEquals(count, index); // there should be the same number of entries in each
    }

    @Test
    void serializeExpectedFCMFamilyTest(final @TempDir Path tmpDir) {
        String savePath = tmpDir.resolve("saved.txt").toString();
        boolean isError = false;

        // Build ExpectedFCMFamily for node0
        ExpectedFCMFamily fcmFamily = new DummyExpectedFCMFamily(0);
        Map<MapKey, ExpectedValue> expectedMap = fcmFamily.getExpectedMap();
        final MapKey key = new MapKey(0, 0, 1);
        final byte[] content = new byte[48];
        random.nextBytes(content);
        ExpectedValue expectedValue = new ExpectedValue();
        expectedValue.setHash(new Hash(content));
        expectedValue.setErrored(true);

        try {
            File fileOne = new File(savePath);
            FileOutputStream fos = new FileOutputStream(fileOne);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            try {
                oos.writeObject(fcmFamily);
            } catch (NotSerializableException e) {
                isError = true;
                e.printStackTrace();
            }
            oos.flush();
            oos.close();
            fos.close();

        } catch (Exception e) {
            e.printStackTrace();
        }
        assertTrue(new File(savePath).exists());
        assertFalse(isError);
    }

    private long getExpirationTime() {
        return Instant.now().getEpochSecond() + FCQ_TTL;
    }
}
