// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.merkledb.reflect;

import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;

public class HalfDiskHashMapW {
    private final HalfDiskHashMap map;
    Class<?> clazz = HalfDiskHashMap.class;
    java.lang.reflect.Field numOfBuckets;
    java.lang.reflect.Field fileCollection;
    java.lang.reflect.Field bucketIndexToBucketLocation;

    public HalfDiskHashMapW(HalfDiskHashMap map) {
        this.map = map;
        try {
            this.numOfBuckets = clazz.getDeclaredField("numOfBuckets");
            this.fileCollection = clazz.getDeclaredField("fileCollection");
            this.bucketIndexToBucketLocation = clazz.getDeclaredField("bucketIndexToBucketLocation");

            numOfBuckets.setAccessible(true);
            fileCollection.setAccessible(true);
            bucketIndexToBucketLocation.setAccessible(true);
        } catch (NoSuchFieldException | SecurityException e) {
            throw new RuntimeException(e);
        }
    }

    // use reflection to get a private field from a class
    public int getNumOfBuckets() {
        try {
            return (int) numOfBuckets.get(map);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public DataFileCollection getFileCollection() {
        try {
            return (DataFileCollection) fileCollection.get(map);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public LongList getBucketIndexToBucketLocation() {
        try {
            return (LongList) bucketIndexToBucketLocation.get(map);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
