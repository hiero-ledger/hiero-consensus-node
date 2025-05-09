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
