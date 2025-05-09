/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.statevalidation.merkledb.reflect;


import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.hashmap.Bucket;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import com.swirlds.virtualmap.VirtualKey;

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
        } catch (IllegalArgumentException | IllegalAccessException  e) {
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
