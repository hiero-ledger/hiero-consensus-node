// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import org.hiero.consensus.model.crypto.Hash;

/**
 * A simple random access list of {@link Hash}es. It acts like a very simple long to {@link Hash} map.
 * Java Collections do not support collections of more than {@link Integer#MAX_VALUE} length, whereas this collection
 * does. We do not implement analogs of many of the {@link java.util.Collection} APIs, because they are not needed.
 * The data structure does not contain a {@code size} method, or any explicit control over the size of the data
 * structure, but sub-classes may impose such restrictions.
 *
 * Concurrent reads are supported. Concurrent writes to <strong>different</strong> indexes are also supported. Writes
 * to the same index are <strong>not</strong> atomic, and therefore lead to unpredictable results.
 *
 * This class was designed to support the on-disk merkle database. These concurrency tradeoffs are acceptable
 * because there is only ever one flushing thread writing to the {@link HashList} and no readers will concurrently
 * read from these write locations because those indexes are covered by the in-memory cache.
 *
 * Implementations of {@link HashList} may include off-heap or even on-disk variants. As such, the Hash objects
 * may be serialized to/from bytes.
 */
public interface HashList extends Closeable {

    /**
     * Get the {@link Hash} at the given index. If the index is greater or equal to {@link #size()},
     * this method returns {@code null}. If the index is negative or greater or equal to {@link
     * #capacity()}, this method throws {@link IndexOutOfBoundsException}.
     *
     * @param index
     * 		the zero-based index to get hash for. Must be non-negative.
     * @return The {@link Hash} at that index, or null if there is no such {@link Hash} stored there.
     * @throws IOException
     * 		If there was a problem constructing the {@link Hash}. This should never happen.
     * @throws IndexOutOfBoundsException
     * 		if the index is less than 0, or if the index is greater than
     * 		the {@link #capacity()}.
     */
    Hash get(long index) throws IOException;

    /**
     * Put a {@link Hash} at the given index.
     *
     * @param index
     * 		the index at which to store the {@link Hash}. Must be non-negative.
     * @param hash
     * 		a non-null hash to write
     * @throws IndexOutOfBoundsException
     * 		if the index is less than 0, or if the index is greater than
     * 		the {@link #capacity()}.
     */
    void put(long index, Hash hash);

    /**
     * Get the maximum capacity of this data structure.
     *
     * @return The maximum capacity. Will be non-negative.
     */
    long capacity();

    /**
     * Get the number of hashes in this hash list.
     *
     * @return The size of the list. Will be non-negative.
     */
    long size();

    /**
     * Write all hashes in this HashList into a file
     *
     * @param file
     * 		The file to write into, it should not exist but its parent directory should exist and be writable.
     * @throws IOException
     * 		If there was a problem creating or writing to the file.
     */
    void writeToFile(Path file) throws IOException;

    @Override
    void close();
}
