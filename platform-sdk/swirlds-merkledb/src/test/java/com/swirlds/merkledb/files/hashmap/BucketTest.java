// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files.hashmap;

import static com.swirlds.merkledb.files.hashmap.HalfDiskHashMap.INVALID_VALUE;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.merkledb.test.fixtures.ExampleLongKey;
import com.swirlds.merkledb.test.fixtures.ExampleVariableKey;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@SuppressWarnings({"RedundantCast", "unchecked", "rawtypes"})
class BucketTest {

    private enum KeyType {
        fixed(ExampleLongKey::longToKey, ExampleLongKey::keyToLong),
        variable(ExampleVariableKey::longToKey, ExampleVariableKey::keyToLong);

        final Function<Long, Bytes> keyConstructor;
        final Function<Bytes, Long> keyValueReader;

        KeyType(final Function<Long, Bytes> keyConstructor, final Function<Bytes, Long> keyValueReader) {
            this.keyConstructor = keyConstructor;
            this.keyValueReader = keyValueReader;
        }

        long getKeyAsLong(final Bytes key) {
            return keyValueReader.apply(key);
        }
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void canSetAndGetBucketIndex(KeyType keyType) {
        final Bucket subject = new Bucket();
        final int pretendIndex = 123;
        subject.setBucketIndex(pretendIndex);
        assertEquals(pretendIndex, subject.getBucketIndex(), "Should be able to get the set index");
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void returnsNotFoundValueFromEmptyBucket(KeyType keyType) throws IOException {
        final Bucket subject = new Bucket();
        final long notFoundValue = 123;
        final Bytes missingKey = keyType.keyConstructor.apply(321L);

        assertEquals(
                notFoundValue,
                subject.findValue(missingKey.hashCode(), missingKey, notFoundValue),
                "Should not find a value in an empty bucket");
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void testBucketAddAndDelete(KeyType keyType) throws IOException {
        // create some keys to test with
        final Bytes[] testKeys = new Bytes[20];
        for (int i = 0; i < testKeys.length; i++) {
            testKeys[i] = keyType.keyConstructor.apply((long) (i + 10));
        }
        // create a bucket
        final Bucket bucket = new Bucket();
        assertEquals(0, bucket.getBucketEntryCount(), "Check we start with empty bucket");
        // insert keys and check
        for (int i = 0; i < 10; i++) {
            final Bytes key = testKeys[i];
            bucket.putValue(key, key.hashCode(), keyType.getKeyAsLong(key) + 100);
            assertEquals(i + 1, bucket.getBucketEntryCount(), "Check we have correct count");
            // check that all keys added so far are there
            for (int j = 0; j <= i; j++) {
                checkKey(keyType, bucket, testKeys[j]);
            }
        }
        assertEquals(10, bucket.getBucketEntryCount(), "Check we have correct count");
        // now update, check and put back
        bucket.putValue(testKeys[5], testKeys[5].hashCode(), 1234);
        assertEquals(
                1234, bucket.findValue(testKeys[5].hashCode(), testKeys[5], -1), "Should get expected value of 1234");
        bucket.putValue(testKeys[5], testKeys[5].hashCode(), 115);
        for (int j = 0; j < 10; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        // now delete last key and check
        bucket.putValue(testKeys[9], testKeys[9].hashCode(), INVALID_VALUE);
        assertEquals(9, bucket.getBucketEntryCount(), "Check we have correct count");
        for (int j = 0; j < 9; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        assertEquals(
                -1,
                bucket.findValue(testKeys[9].hashCode(), testKeys[9], -1),
                "Should not find entry 10 any more we deleted it");
        // now delete a middle, index 5
        bucket.putValue(testKeys[5], testKeys[5].hashCode(), INVALID_VALUE);
        assertEquals(8, bucket.getBucketEntryCount(), "Check we have correct count");
        for (int j = 0; j < 5; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        for (int j = 6; j < 9; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        assertEquals(
                -1,
                bucket.findValue(testKeys[5].hashCode(), testKeys[5], -1),
                "Should not find entry 5 any more we deleted it");
        // now delete first, index 0
        bucket.putValue(testKeys[0], testKeys[0].hashCode(), INVALID_VALUE);
        assertEquals(7, bucket.getBucketEntryCount(), "Check we have correct count");
        for (int j = 1; j < 5; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        for (int j = 6; j < 9; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        assertEquals(
                -1,
                bucket.findValue(testKeys[0].hashCode(), testKeys[0], -1),
                "Should not find entry 0 any more we deleted it");
        // add two more entries and check
        bucket.putValue(testKeys[10], testKeys[10].hashCode(), 120);
        bucket.putValue(testKeys[11], testKeys[11].hashCode(), 121);
        assertEquals(9, bucket.getBucketEntryCount(), "Check we have correct count");
        for (int j = 1; j < 5; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        for (int j = 6; j < 9; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        for (int j = 10; j < 12; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
        // put 0, 5 and 9 back in, and check we have full range
        bucket.putValue(testKeys[0], testKeys[0].hashCode(), 110);
        bucket.putValue(testKeys[5], testKeys[5].hashCode(), 115);
        bucket.putValue(testKeys[9], testKeys[9].hashCode(), 119);
        assertEquals(12, bucket.getBucketEntryCount(), "Check we have correct count");
        for (int j = 0; j < 12; j++) {
            checkKey(keyType, bucket, testKeys[j]);
        }
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void testBucketGrowing(KeyType keyType) {
        // create some keys to test with
        final Bytes[] testKeys = new Bytes[420];
        for (int i = 0; i < testKeys.length; i++) {
            testKeys[i] = keyType.keyConstructor.apply((long) (i + 10));
        }
        // create a bucket
        final Bucket bucket = new Bucket();
        assertEquals(0, bucket.getBucketEntryCount(), "Check we start with empty bucket");
        // insert keys and check
        for (int i = 0; i < testKeys.length; i++) {
            final Bytes key = testKeys[i];
            bucket.putValue(key, key.hashCode(), keyType.getKeyAsLong(key) + 100);
            assertEquals(i + 1, bucket.getBucketEntryCount(), "Check we have correct count");
            // check that all keys added so far are there
            for (int j = 0; j <= i; j++) {
                checkKey(keyType, bucket, testKeys[j]);
            }
        }
        assertEquals(testKeys.length, bucket.getBucketEntryCount(), "Check we have correct count");
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void testBucketImportExportClear(KeyType keyType) throws IOException {
        // create some keys to test with
        final Bytes[] testKeys = new Bytes[50];
        for (int i = 0; i < testKeys.length; i++) {
            testKeys[i] = keyType.keyConstructor.apply((long) (i + 10));
        }
        // create a bucket
        final Bucket bucket = new Bucket();
        assertEquals(0, bucket.getBucketEntryCount(), "Check we start with empty bucket");
        // insert keys and check
        for (int i = 0; i < testKeys.length; i++) {
            final Bytes key = testKeys[i];
            bucket.putValue(key, key.hashCode(), keyType.getKeyAsLong(key) + 100);
            assertEquals(i + 1, bucket.getBucketEntryCount(), "Check we have correct count");
            // check that all keys added so far are there
            for (int j = 0; j <= i; j++) {
                checkKey(keyType, bucket, testKeys[j]);
            }
        }
        assertEquals(testKeys.length, bucket.getBucketEntryCount(), "Check we have correct count");
        // get raw bytes first to compare to
        final int size = bucket.sizeInBytes();
        final byte[] goodBytes = new byte[size];
        final BufferedData bucketData = BufferedData.wrap(goodBytes);
        bucket.writeTo(bucketData);
        final String goodBytesStr = Arrays.toString(goodBytes);
        // now test write to buffer
        final BufferedData bbuf = BufferedData.allocate(size);
        bucket.writeTo(bbuf);
        bbuf.flip();
        assertEquals(
                goodBytesStr, Arrays.toString(bbuf.getBytes(0, bbuf.length()).toByteArray()), "Expect bytes to match");

        // create new bucket with good bytes and check it is the same
        final Bucket bucket2 = new Bucket();
        bucket2.readFrom(BufferedData.wrap(goodBytes));
        assertEquals(bucket.toString(), bucket2.toString(), "Expect bucket toStrings to match");

        // test clear
        final Bucket bucket3 = new Bucket();
        bucket.clear();
        assertEquals(bucket3.toString(), bucket.toString(), "Expect bucket toStrings to match");
    }

    @Test
    void toStringAsExpectedForBucket() {
        final Bucket bucket = new Bucket();

        final String emptyBucketRepr = "Bucket{bucketIndex=0, entryCount=0, size=5}";
        assertEquals(emptyBucketRepr, bucket.toString(), "Empty bucket should represent as expected");

        final String bucketWithIndex0Repr = "Bucket{bucketIndex=0, entryCount=0, size=5}";
        bucket.setBucketIndex(0);
        assertEquals(bucketWithIndex0Repr, bucket.toString(), "Empty bucket should represent as expected");

        final String bucketWithIndex1Repr = "Bucket{bucketIndex=1, entryCount=0, size=5}";
        bucket.setBucketIndex(1);
        assertEquals(bucketWithIndex1Repr, bucket.toString(), "Empty bucket should represent as expected");

        final Bytes key = ExampleLongKey.longToKey(2056);
        bucket.putValue(key, key.hashCode(), 5124);
        bucket.setBucketIndex(0);
        final String nonEmptyBucketRepr = "Bucket{bucketIndex=0, entryCount=1, size=31}";
        assertEquals(nonEmptyBucketRepr, bucket.toString(), "Non-empty bucket represent as expected");
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void emptyParsedBucketToBucketIndexZero(final KeyType keyType) throws IOException {
        final Bucket inBucket = new ParsedBucket();
        final Bytes key1 = keyType.keyConstructor.apply(1L);
        final Bytes key2 = keyType.keyConstructor.apply(2L);
        inBucket.setBucketIndex(0);
        inBucket.putValue(key1, key1.hashCode(), 2);
        inBucket.putValue(key2, key2.hashCode(), 1);
        final BufferedData buf = BufferedData.allocate(inBucket.sizeInBytes());
        inBucket.writeTo(buf);
        buf.reset();
        final Bucket outBucket = new Bucket();
        outBucket.readFrom(buf);
        outBucket.putValue(key1, key1.hashCode(), INVALID_VALUE);
        outBucket.putValue(key2, key2.hashCode(), INVALID_VALUE);
        assertDoesNotThrow(outBucket::getBucketIndex);
        assertDoesNotThrow(outBucket::getBucketEntryCount);
        assertEquals(0, outBucket.getBucketEntryCount());
        assertEquals(0, outBucket.getBucketIndex());
    }

    @ParameterizedTest
    @EnumSource(KeyType.class)
    void parsedBucketPutIfEqual(final KeyType keyType) {
        final Bytes key1 = keyType.keyConstructor.apply(1L);
        final Bucket bucket = new ParsedBucket();
        assertDoesNotThrow(() -> bucket.putValue(key1, key1.hashCode(), INVALID_VALUE, 1));
    }

    @Test
    void keyEqualsKeyTooShortTest() throws IOException {
        final Bytes keyInBucket = Bytes.wrap(new byte[] {1, 2, 3, 4});
        final Bytes keyToSearch = Bytes.wrap(new byte[] {1, 2});
        // Let's pretend both keys have the same hash code
        final int hashCode = 111;
        final int value = 1;
        try (final Bucket bucket = new Bucket()) {
            bucket.putValue(keyInBucket, hashCode, value);
            assertEquals(value, bucket.findValue(hashCode, keyInBucket, -1));
            assertEquals(-1, bucket.findValue(hashCode, keyToSearch, -1));
        }
    }

    @Test
    void keyEqualsKeyTooLongTest() throws IOException {
        final Bytes keyInBucket = Bytes.wrap(new byte[] {1, 2, 3, 4});
        final Bytes keyToSearch = Bytes.wrap(new byte[] {1, 2, 3, 4, 5, 6});
        // Let's pretend both keys have the same hash code
        final int hashCode = 111;
        final int value = 1;
        try (final Bucket bucket = new Bucket()) {
            bucket.putValue(keyInBucket, hashCode, value);
            assertEquals(value, bucket.findValue(hashCode, keyInBucket, -1));
            assertEquals(-1, bucket.findValue(hashCode, keyToSearch, -1));
        }
    }

    @Test
    void keyEqualsKeyEmptyTest() throws IOException {
        final Bytes keyInBucket = Bytes.wrap(new byte[] {1, 2, 3, 4});
        final Bytes keyToSearch = Bytes.wrap(new byte[0]);
        // Let's pretend both keys have the same hash code
        final int hashCode = 111;
        final int value = 1;
        try (final Bucket bucket = new Bucket()) {
            bucket.putValue(keyInBucket, hashCode, value);
            assertEquals(value, bucket.findValue(hashCode, keyInBucket, -1));
            assertEquals(-1, bucket.findValue(hashCode, keyToSearch, -1));
        }
    }

    private void checkKey(KeyType keyType, Bucket bucket, Bytes key) {
        var findResult =
                assertDoesNotThrow(() -> bucket.findValue(key.hashCode(), key, -1), "No exception should be thrown");
        assertEquals(keyType.getKeyAsLong(key) + 100, findResult, "Should get expected value");
    }
}
