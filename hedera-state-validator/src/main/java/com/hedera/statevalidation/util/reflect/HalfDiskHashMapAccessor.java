// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util.reflect;

import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.hashmap.HalfDiskHashMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;

/**
 * Provides reflective access to private {@link HalfDiskHashMap} fields.
 */
@SuppressWarnings("SpellCheckingInspection")
public final class HalfDiskHashMapAccessor {

    private final HalfDiskHashMap hdhm;

    private final Field fileCollection;
    private final Field bucketIndexToBucketLocation;

    public HalfDiskHashMapAccessor(@NonNull final HalfDiskHashMap hdhm) {
        this.hdhm = hdhm;
        try {
            final Class<?> clazz = HalfDiskHashMap.class;
            this.fileCollection = clazz.getDeclaredField("fileCollection");
            this.bucketIndexToBucketLocation = clazz.getDeclaredField("bucketIndexToBucketLocation");

            fileCollection.setAccessible(true);
            bucketIndexToBucketLocation.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    // use reflection to get a private field from a class
    public DataFileCollection getFileCollection() {
        try {
            return (DataFileCollection) fileCollection.get(hdhm);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public LongList getBucketIndexToBucketLocation() {
        try {
            return (LongList) bucketIndexToBucketLocation.get(hdhm);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
