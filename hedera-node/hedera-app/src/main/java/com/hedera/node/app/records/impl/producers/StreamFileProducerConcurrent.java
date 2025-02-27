// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers;

import static java.util.Objects.requireNonNull;
import static java.util.concurrent.CompletableFuture.completedFuture;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.node.app.annotations.CommonExecutor;
import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A highly concurrent implementation of {@link BlockRecordStreamProducer}.
 *
 * <p>Threading model: is really important here, This class depends on the fact that public methods are all called on
 * the handle transaction thread, so they are single threaded and executed in the correct order. The information
 * provided by calls to the public methods are then processed into files by the background threads. All async task are
 * done with {@link CompletableFuture}s.
 */
@Singleton
public final class StreamFileProducerConcurrent implements BlockRecordStreamProducer {
    /** Simple pair class */
    record TwoResults<A, B>(A a, B b) {}

    /** The logger */
    private static final Logger logger = LogManager.getLogger(StreamFileProducerConcurrent.class);
    /** Creates new {@link BlockRecordWriter} instances */
    private final BlockRecordWriterFactory writerFactory;
    /** The HAPI protobuf version. Does not change during execution. */
    private final SemanticVersion hapiVersion;
    /** The {@link BlockRecordFormat} used to serialize items for output. */
    private final BlockRecordFormat format;
    /** The executor service to use for background tasks */
    private final ExecutorService executorService;
    /** The lock to protect the following state */
    private final Lock lock = new ReentrantLock();
    /** Future for running hash results of last running hash updates task */
    private CompletableFuture<Bytes> lastRecordHashingResult = null;
    /** Future for running hash results of previous lastRecordHashingResult */
    private CompletableFuture<Bytes> lastRecordHashingResultNMinus1 = null;
    /** Future for running hash results of previous, previous lastRecordHashingResult */
    private CompletableFuture<Bytes> lastRecordHashingResultNMinus2 = null;
    /** Future for running hash results of previous, previous, previous lastRecordHashingResult */
    private CompletableFuture<Bytes> lastRecordHashingResultNMinus3 = null;
    /**
     * Future for the current record file writer, this will always get you the record file writer for the current file
     * being written
     */
    private CompletableFuture<BlockRecordWriter> currentRecordFileWriter = null;
    /** Set in {@link #switchBlocks(long, long, Instant)}, keeps track of the current block number. */
    private long currentBlockNumber;

    /**
     * Construct {@link StreamFileProducerConcurrent}
     *
     * @param format          The format to use for the record stream
     * @param writerFactory   the factory used to create new {@link BlockRecordWriter} instances
     * @param executorService The executor service to use for background threads
     */
    @Inject
    public StreamFileProducerConcurrent(
            @NonNull final BlockRecordFormat format,
            @NonNull final BlockRecordWriterFactory writerFactory,
            @CommonExecutor @NonNull final ExecutorService executorService,
            @NonNull final SemanticVersion hapiVersion) {
        this.writerFactory = requireNonNull(writerFactory);
        this.format = requireNonNull(format);
        this.hapiVersion = requireNonNull(hapiVersion);
        this.executorService = requireNonNull(executorService);
    }

    // =================================================================================================================
    // Implementation of BlockRecordStreamProducer methods
    //
    // These methods are ALWAYS called on the "handle" thread.

    /** {@inheritDoc} */
    @Override
    public void initRunningHash(@NonNull final RunningHashes runningHashes) {
        lock.lock(); // Block until the lock is acquired
        try {
            if (lastRecordHashingResult != null) {
                throw new IllegalStateException("initRunningHash() can only be called once");
            }

            if (runningHashes.runningHash().equals(Bytes.EMPTY)) {
                throw new IllegalArgumentException("The initial running hash cannot be empty");
            }

            lastRecordHashingResult = completedFuture(runningHashes.runningHash());
            lastRecordHashingResultNMinus1 = completedFuture(runningHashes.nMinus1RunningHash());
            lastRecordHashingResultNMinus2 = completedFuture(runningHashes.nMinus2RunningHash());
            lastRecordHashingResultNMinus3 = completedFuture(runningHashes.nMinus3RunningHash());
        } finally {
            lock.unlock(); // Always unlock.
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Bytes getRunningHash() {
        assert lastRecordHashingResult != null : "initRunningHash() must be called before getRunningHash()";
        return lastRecordHashingResult.join();
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes getNMinus3RunningHash() {
        assert lastRecordHashingResultNMinus3 != null
                : "initRunningHash() must be called before lastRecordHashingResultNMinus3()";
        return lastRecordHashingResultNMinus3.join();
    }

    /** {@inheritDoc} */
    @Override
    public void switchBlocks(
            final long lastBlockNumber,
            final long newBlockNumber,
            @NonNull final Instant newBlockFirstTransactionConsensusTime) {
        lock.lock(); // Block until the lock is acquired

        try {
            assert lastRecordHashingResult != null : "initRunningHash() must be called before switchBlocks";
            if (newBlockNumber != lastBlockNumber + 1) {
                throw new IllegalArgumentException("Block numbers must be sequential, newBlockNumber=" + newBlockNumber
                        + ", lastBlockNumber=" + lastBlockNumber);
            }

            this.currentBlockNumber = newBlockNumber;
            requireNonNull(newBlockFirstTransactionConsensusTime);

            if (currentRecordFileWriter == null) {
                // We are at the start of a new block and there is no old one to close or wait for. So just create a new
                // one which creates a new file and writes initializes it in the background
                currentRecordFileWriter = lastRecordHashingResult.thenApply(lastRunningHash -> createBlockRecordWriter(
                        lastRunningHash, newBlockFirstTransactionConsensusTime, newBlockNumber));
            } else {
                // Reassign our fileWriter future to a future that will complete once:
                //   (1) The running hash of the last record in the current block is available; and,
                //   (2) We have finished writing and closed the current block's record file.
                // The VALUE of this future, when it completes, will be the writer for the file of
                // the new block we are just starting.
                currentRecordFileWriter = currentRecordFileWriter
                        .thenCombine(lastRecordHashingResult, TwoResults::new)
                        .thenApplyAsync(
                                twoResults -> {
                                    final var writer = twoResults.a();
                                    final var lastRunningHash = twoResults.b();
                                    closeWriter(writer, lastRunningHash);
                                    return createBlockRecordWriter(
                                            lastRunningHash, newBlockFirstTransactionConsensusTime, newBlockNumber);
                                },
                                executorService);
            }
        } finally {
            lock.unlock(); // Always unlock.
        }
    }

    /**
     * Write record items to stream files. They must be in exact consensus time order! This must only be called after the user
     * transaction has been committed to state and is 100% done.
     *
     * @param recordStreamItems the record stream items to write
     */
    @Override
    public void writeRecordStreamItems(@NonNull final Stream<SingleTransactionRecord> recordStreamItems) {
        lock.lock(); // Block until the lock is acquired
        try {
            assert lastRecordHashingResult != null : "initRunningHash() must be called before writeRecordStreamItems";
            // FUTURE: consider implications on a reconnect scenario! When the currentRecordFileWriter isn't created in
            // the same way, there can be NPE's
            requireNonNull(recordStreamItems);

            // serialize all the record stream items in background thread into SerializedSingleTransaction objects
            final var futureSerializedRecords = CompletableFuture.supplyAsync(
                    () -> recordStreamItems
                            .map(item -> format.serialize(item, currentBlockNumber, hapiVersion))
                            .toList(),
                    executorService);
            // when serialization is done and previous running hash is computed, we can compute new running hash and
            // write serialized items to record file in parallel update running hash in a background thread
            // Even though we don't update the currentRecordFileWriter until the end, we still need to update the
            // running hashes here for every transaction even if the currentRecordFileWriter is null to be compatible
            // with the mono-service implementation.
            lastRecordHashingResultNMinus3 = lastRecordHashingResultNMinus2;
            lastRecordHashingResultNMinus2 = lastRecordHashingResultNMinus1;
            lastRecordHashingResultNMinus1 = lastRecordHashingResult;
            lastRecordHashingResult = lastRecordHashingResult
                    .thenCombine(futureSerializedRecords, TwoResults::new)
                    .thenApplyAsync(
                            twoResults -> format.computeNewRunningHash(twoResults.a(), twoResults.b()),
                            executorService);
            // When the currentRecordFileWriter is null, it means that the node is starting from genesis or restarting
            // at an upgrade boundary. In either case, there is no previous record file to write to. So we just ignore
            // the serialized items.
            if (currentRecordFileWriter == null) {
                // For a node starting from genesis or restarting at an upgrade boundary, switchBlocks() is
                // always called before the first call to writeRecordStreamItems(), because
                // BlockRecordManagerImpl#startTransaction() will detect either the genesis special case
                // or notice more than two seconds have passed since the last block was created before the
                // upgrade. However, a node that is restarted in the middle of a block will follow this
                // pattern; nor will a node going through event stream recovery.
                return;
            }
            // write serialized items to record file in a background thread
            currentRecordFileWriter = currentRecordFileWriter
                    .thenCombine(futureSerializedRecords, TwoResults::new)
                    .thenApplyAsync(
                            twoResults -> {
                                final var writer = twoResults.a();
                                final var serializedItems = twoResults.b();
                                serializedItems.forEach(item -> {
                                    try {
                                        writer.writeItem(item);
                                    } catch (final Exception e) {
                                        logger.error("Error writing record item to file", e);
                                    }
                                });
                                return writer;
                            },
                            executorService);
        } finally {
            lock.unlock(); // Always unlock.
        }
    }

    /**
     * Closes this StreamFileProducerBase wait for any background thread, close all files etc. This method is
     * synchronous, so waits for all background threads to finish and files to be closed. This method is thread-safe and
     * can be called outside the context of the handle thread.
     */
    @Override
    public void close() {
        lock.lock(); // Block until the lock is acquired

        try {
            if (currentRecordFileWriter != null) {
                CompletableFuture.allOf(currentRecordFileWriter, lastRecordHashingResult)
                        .thenAccept(aVoid -> {
                            final var writer = currentRecordFileWriter.join();
                            final var lastRunningHash = lastRecordHashingResult.join();
                            closeWriter(writer, lastRunningHash);
                        })
                        .join();

                lastRecordHashingResult = null;
                lastRecordHashingResultNMinus1 = null;
                lastRecordHashingResultNMinus2 = null;
                lastRecordHashingResultNMinus3 = null;
                currentRecordFileWriter = null;
            }
        } finally {
            lock.unlock(); // Always unlock.
        }
    }

    // =================================================================================================================
    // private implementation

    private BlockRecordWriter createBlockRecordWriter(
            @NonNull Bytes lastRunningHash, @NonNull final Instant startConsensusTime, final long blockNumber) {
        try {
            logger.debug("Starting new block record file for block {}", blockNumber);
            final var writer = writerFactory.create();
            final var startRunningHash = asHashObject(lastRunningHash);
            writer.init(hapiVersion, startRunningHash, startConsensusTime, blockNumber);
            return writer;
        } catch (final Exception e) {
            logger.error("Error creating record file writer", e);
            throw e;
        }
    }

    private void closeWriter(BlockRecordWriter writer, Bytes lastRunningHash) {
        // An error here is bad news. But at least, let us catch this error and log it, and
        // move forward with the next block.
        try {
            writer.close(asHashObject(lastRunningHash));
        } catch (final Exception e) {
            logger.error("Error closing record file writer", e);
        }
    }

    private HashObject asHashObject(@NonNull final Bytes hash) {
        return new HashObject(HashAlgorithm.SHA_384, (int) hash.length(), hash);
    }
}
