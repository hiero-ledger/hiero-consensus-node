// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.merkledb.reflect;

import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;

public class MemoryIndexDiskKeyValueStoreW<T> {
    MemoryIndexDiskKeyValueStore memoryIndexDiskKeyValue;
    Class<?> clazz = MemoryIndexDiskKeyValueStore.class;
    java.lang.reflect.Field fileCollection; // DataFileCollection

    public MemoryIndexDiskKeyValueStoreW(MemoryIndexDiskKeyValueStore memoryIndexDiskKeyValue) {
        this.memoryIndexDiskKeyValue = memoryIndexDiskKeyValue;
    }

    @SuppressWarnings("unchecked")
    public DataFileCollection getFileCollection() {
        try {
            fileCollection = clazz.getDeclaredField("fileCollection");
            fileCollection.setAccessible(true);
            return (DataFileCollection) fileCollection.get(memoryIndexDiskKeyValue);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
