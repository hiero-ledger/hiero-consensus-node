// SPDX-License-Identifier: Apache-2.0
/**
 * This package is responsible for the management of records.
 *
 * <p>A consensus node takes as input transactions, and produces as output "blocks" that form a "blockchain". For
 * Hedera, these blocks are encapsulated in "record files". The actual format of these record files has changed over
 * time. The version 6 of this file was the first to depend almost entirely upon protobuf, but had many problems related
 * to how hashing was done. Version 7 of the file cleans those things up. Conceptually, a "block" is a collection of
 * {@link com.hedera.hapi.streams.RecordStreamItem}s, where a rolling hash of each item is made and the rolling hash of
 * the last item is included as the hash in the next record file, thus forming a chain. For historical reasons, the
 * term "record" is overloaded -- it means the result of running a transaction, a record stream item, and a record file.
 * The term "block" is also overloaded, sometimes synonymous with "record file", and sometimes meaning a "round" and
 * sometimes meaning the collection of record stream items!
 *
 * <p>These record files are exported to file servers. Historically, these have been AWS S3 and Google Cloud buckets
 * (object storage). The consensus node produces a file and writes it to disk (one file per record/block), and another
 * process picks up that file and uploads it to the file server. In the future, the consensus node will connect to
 * file servers and push the records directly, only buffering a few records in memory if connection with the destination
 * is unavailable. If records cannot be uploaded, then the consensus node should apply backpressure and refuse to
 * continue working. The danger is if every node were to fail to produce records, but continued to process events and
 * mutate state, then we could end in a situation where state has moved forward but with no record of the transactions
 * that produced those state changes. This would destroy the blockchain. Thus, if records cannot be uploaded, then the
 * node should refuse to do more work, with a small buffer for periodic network "weather".
 *
 * <p>When the Hashgraph comes to consensus on a round, it calls the
 * {@link com.hedera.node.app.workflows.handle.HandleWorkflow} to handle all the transactions in that round. The current
 * implementation produces a block every 2 seconds of consensus time, regardless of the round boundary. In the future,
 * it will produce a block ever N number of whole rounds, or possibly every round.
 *
 * <p>{@link com.hedera.node.app.records.BlockRecordManager} is the main interface between the record management
 * system and the rest of the application code. It implements {@link com.hedera.node.app.spi.records.BlockRecordInfo},
 * which is used by services to get information from the record management system, without being exposed to all the
 * privileged and gory details. The {@code BlockRecordManager} is only used during handling of transactions. An API
 * on the manager exists so as to be notified of the consensus time at the start of every transaction, to be given the
 * set of record items produced by every transaction, and one notification of the end of every round.
 *
 * <p>A compliant implementation may employ one or more background threads for processing the
 * {@link com.hedera.hapi.streams.RecordStreamItem}s produced by a transaction, or it may block the handle-transaction
 * thread until the data has been hashed and, if needed, uploaded to an end destination file server or mirror node.
 * Architecturally it doesn't matter either way, it is just a performance optimization.
 */
package com.hedera.node.app.records;
