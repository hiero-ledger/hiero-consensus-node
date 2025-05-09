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

import com.swirlds.merkledb.files.hashmap.ParsedBucket;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

public class BucketIterator {
    private Iterator<ParsedBucket.BucketEntry> iterator;

    public BucketIterator(ParsedBucket bucket) {
        try {
            Field entriesField = ParsedBucket.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            List<ParsedBucket.BucketEntry> entries = (List<ParsedBucket.BucketEntry>) entriesField.get(bucket);
            iterator = entries.iterator();
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hasNext() {
        return iterator.hasNext();
    }

    public ParsedBucket.BucketEntry next() {
        try {
            return iterator.next();

        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

}
