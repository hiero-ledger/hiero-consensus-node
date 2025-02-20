// SPDX-License-Identifier: Apache-2.0
/**
 * Provides implementations of {@link com.hedera.node.app.records.impl.BlockRecordStreamProducer}. At startup, a single
 * implementation is selected, based on configuration, and used through the duration of the node's execution. There are
 * several possible implementations, some of which currently exist, and some which could yet be created:
 * <ul>
 *     <li>{@link com.hedera.node.app.records.impl.producers.StreamFileProducerSingleThreaded} is an implementation
 *     where all stream producer activities are blocking and take place on the handle thread. This implementation is
 *     particularly useful in testing and in the "Hedera Local Node" when run in "dev" mode, in which smart contract
 *     applications want a lightweight, blocking, test server to utilize.</li>
 *     <li>{@link com.hedera.node.app.records.impl.producers.StreamFileProducerConcurrent} is a heavily concurrent
 *     implementation utilizing the fork-join framework and extensive use of
 *     {@link java.util.concurrent.CompletableFuture}. This implementation may provide the best performance at the
 *     cost of increased complexity</li>
 *     <li>An as-yet unimplemented alternative concurrent producer could be written utilizing a work queue and a single
 *     background thread. It is something of a mixture between the single-threaded and concurrent implementations in
 *     terms of complexity and performance.</li>
 * </ul>
 *
 * <p>Each of these implementations delegates to a {@link com.hedera.node.app.records.impl.producers.BlockRecordWriter}
 * for actually writing the records to a file, socket, or other destination. The core focus on the producer is to
 * handle the business logic and threading logic irrespective of the actual output file format or output destination
 * details. They also take the {@link com.hedera.hapi.streams.RecordStreamItem}s representing the results of each
 * user transaction, and convert them into
 * {@link com.hedera.node.app.records.impl.producers.SerializedSingleTransactionRecord}s. Ideally there would be little
 * or no conversion needed, but the v6 version of the record file requires substantial conversion.
 *
 * <p>A single instance of one of the {@link com.hedera.node.app.records.impl.BlockRecordStreamProducer} implementations
 * is selected at startup and used throughout the execution of the system. However, a new instance of
 * {@link com.hedera.node.app.records.impl.producers.BlockRecordWriter} is created for each block of transactions.
 */
package com.hedera.node.app.records.impl.producers;
