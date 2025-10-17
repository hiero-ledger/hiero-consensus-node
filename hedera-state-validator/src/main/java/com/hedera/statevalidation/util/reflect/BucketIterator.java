// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.util.reflect;

import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;

/**
 * Provides iteration over entries in a {@link ParsedBucket} by using reflection to access
 * its internal entries list. This utility enables traversal of bucket entries when direct
 * access is not available through the public API.
 */
public final class BucketIterator {

    private final Iterator<ParsedBucket.BucketEntry> iterator;

    public BucketIterator(@NonNull final ParsedBucket bucket) {
        try {
            final Field entriesField = ParsedBucket.class.getDeclaredField("entries");
            entriesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            final List<ParsedBucket.BucketEntry> entries = (List<ParsedBucket.BucketEntry>) entriesField.get(bucket);
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
