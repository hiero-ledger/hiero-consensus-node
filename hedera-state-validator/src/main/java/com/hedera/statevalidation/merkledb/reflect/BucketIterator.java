// SPDX-License-Identifier: Apache-2.0
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

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
