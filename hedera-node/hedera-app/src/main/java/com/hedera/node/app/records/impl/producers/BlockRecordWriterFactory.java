// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers;

/**
 * Creates a new {@link BlockRecordWriter} instance on demand, based on configuration. During processing of
 * transactions, when we determine it is time to write a new block to file, then we need to create a new
 * {@link BlockRecordWriter} instance. This factory is used to create that instance. Creation of the instance may fail,
 * for example, if the filesystem is full or the network destination is unavailable. Callers must be prepared to deal
 * with these failures.
 */
public interface BlockRecordWriterFactory {
    /**
     * Create a new {@link BlockRecordWriter} instance.
     *
     * @return the new instance
     * @throws RuntimeException if creation fails
     */
    BlockRecordWriter create() throws RuntimeException;
}
