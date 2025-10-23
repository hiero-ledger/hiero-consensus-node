// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util.reflect;

import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.MemoryIndexDiskKeyValueStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;

/**
 * Provides reflective access to private {@link MemoryIndexDiskKeyValueStore} fields.
 */
public class MemoryIndexDiskKeyValueStoreAccessor {

    private final MemoryIndexDiskKeyValueStore memoryIndexDiskKeyValue;

    private final Field fileCollection;

    public MemoryIndexDiskKeyValueStoreAccessor(@NonNull final MemoryIndexDiskKeyValueStore memoryIndexDiskKeyValue) {
        try {
            this.memoryIndexDiskKeyValue = memoryIndexDiskKeyValue;
            final Class<?> clazz = MemoryIndexDiskKeyValueStore.class;
            // DataFileCollection
            this.fileCollection = clazz.getDeclaredField("fileCollection");
            fileCollection.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    public DataFileCollection getFileCollection() {
        try {
            return (DataFileCollection) fileCollection.get(memoryIndexDiskKeyValue);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
