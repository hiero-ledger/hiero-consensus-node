// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl.producers;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.blockrecords.RunningHashes;
import com.hedera.hapi.streams.HashAlgorithm;
import com.hedera.hapi.streams.HashObject;
import com.hedera.node.app.records.impl.BlockRecordStreamProducer;
import com.hedera.node.app.state.SingleTransactionRecord;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A single threaded implementation of {@link BlockRecordStreamProducer} where all operations happen in a blocking
 * manner on the calling "handle" thread. This implementation is useful for testing and for the "Hedera Local Node",
 * which is a version of a consensus node that may be run as a standalone application, useful for development.
 */
public final class StreamFileProducerSingleThreaded implements BlockRecordStreamProducer {
    /** The logger */
    private static final Logger logger = LogManager.getLogger(StreamFileProducerSingleThreaded.class);
    /** Creates new {@link BlockRecordWriter} instances */
    private final BlockRecordWriterFactory writerFactory;
    /** The HAPI protobuf version. Does not change during execution. */
    private final SemanticVersion hapiVersion;
    /** The {@link BlockRecordFormat} used to serialize items for output. */
    private final BlockRecordFormat format;
    /**
     * The {@link BlockRecordWriter} to use for writing produced block records. A new one is created at the beginning
     * of each new block.
     */
    private BlockRecordWriter writer;
    /** The running hash at end of the last user transaction */
    private Bytes runningHash = null;
    /** The previous running hash */
    private Bytes runningHashNMinus1 = null;
    /** The previous, previous running hash */
    private Bytes runningHashNMinus2 = null;
    /** The previous, previous, previous running hash */
    private Bytes runningHashNMinus3 = null;

    private long currentBlockNumber = 0;

    /**
     * Construct RecordManager and start background thread
     *
     * @param format        The format to use for the record stream
     * @param writerFactory constructs the writers for the record stream, one per record file
     * @param hapiVersion
     */
    @Inject
    public StreamFileProducerSingleThreaded(
            @NonNull final BlockRecordFormat format,
            @NonNull final BlockRecordWriterFactory writerFactory,
            final SemanticVersion hapiVersion) {
        this.writerFactory = requireNonNull(writerFactory);
        this.format = requireNonNull(format);
        this.hapiVersion = hapiVersion;
    }

    // =========================================================================================================================================================================
    // public methods

    /** {@inheritDoc} */
    @Override
    public void switchBlocks(
            final long lastBlockNumber,
            final long newBlockNumber,
            @NonNull final Instant newBlockFirstTransactionConsensusTime) {

        if (newBlockNumber != lastBlockNumber + 1) {
            throw new IllegalArgumentException("Block numbers must be sequential, newBlockNumber=" + newBlockNumber
                    + ", lastBlockNumber=" + lastBlockNumber);
        }
        requireNonNull(newBlockFirstTransactionConsensusTime);
        this.currentBlockNumber = newBlockNumber;
        final var lastRunningHash = asHashObject(getRunningHash());
        closeWriter(lastRunningHash, lastBlockNumber);
        openWriter(newBlockNumber, lastRunningHash, newBlockFirstTransactionConsensusTime);
        logger.debug(
                "Initializing block record writer for block {} with running hash {}",
                currentBlockNumber,
                lastRunningHash);
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        closeWriter(asHashObject(getRunningHash()), currentBlockNumber);

        runningHash = null;
        runningHashNMinus1 = null;
        runningHashNMinus2 = null;
        runningHashNMinus3 = null;
    }

    /** {@inheritDoc} */
    @Override
    public void initRunningHash(@NonNull final RunningHashes runningHashes) {
        if (runningHash != null) {
            throw new IllegalStateException("initRunningHash() must only be called once");
        }

        if (runningHashes.runningHash().equals(Bytes.EMPTY)) {
            throw new IllegalArgumentException("The initial running hash cannot be empty");
        }

        runningHash = runningHashes.runningHash();
        runningHashNMinus1 = runningHashes.nMinus1RunningHash();
        runningHashNMinus2 = runningHashes.nMinus2RunningHash();
        runningHashNMinus3 = runningHashes.nMinus3RunningHash();
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public Bytes getRunningHash() {
        assert runningHash != null : "initRunningHash() must be called before getRunningHash()";
        return runningHash;
    }

    /** {@inheritDoc} */
    @Nullable
    @Override
    public Bytes getNMinus3RunningHash() {
        return runningHashNMinus3;
    }

    /**
     * Write record items to stream files. They must be in exact consensus time order! This must only be called after the user
     * transaction has been committed to state and is 100% done.
     *
     * @param recordStreamItems the record stream items to write
     */
    @Override
    public void writeRecordStreamItems(@NonNull final Stream<SingleTransactionRecord> recordStreamItems) {
        // Serialize record stream items
        final var serializedItems = recordStreamItems
                .map(item -> format.serialize(item, currentBlockNumber, hapiVersion))
                .toList();
        // Compute new running hash, by adding each serialized record stream item to the current running hash
        runningHashNMinus3 = runningHashNMinus2;
        runningHashNMinus2 = runningHashNMinus1;
        runningHashNMinus1 = runningHash;
        runningHash = format.computeNewRunningHash(runningHash, serializedItems);

        // Write serialized items to the block writer
        serializedItems.forEach(item -> {
            try {
                writer.writeItem(item);
            } catch (final Exception e) {
                // This **may** prove fatal. The node should be able to carry on, but then fail when it comes to
                // actually producing a valid record stream file. We need to have some way of letting all nodes know
                // that this node has a problem, so we can make sure at least a minimal threshold of nodes is
                // successfully producing a blockchain.
                logger.error(
                        "Error writing record stream item to block record writer for block {}", currentBlockNumber, e);
            }
        });
    }

    // =================================================================================================================
    // private implementation

    private HashObject asHashObject(@NonNull final Bytes hash) {
        return new HashObject(HashAlgorithm.SHA_384, (int) hash.length(), hash);
    }

    private void closeWriter(@NonNull final HashObject lastRunningHash, final long lastBlockNumber) {
        if (writer != null) {
            logger.debug(
                    "Closing block record writer for block {} with running hash {}", lastBlockNumber, lastRunningHash);

            // If we fail to close the writer, then this node is almost certainly going to end up in deep trouble.
            // Make sure this is logged. In the FUTURE we may need to do something more drastic, like shut down the
            // node, or maybe retry a number of times before giving up.
            try {
                writer.close(lastRunningHash);
            } catch (final Exception e) {
                logger.error("Error closing block record writer for block {}", lastBlockNumber, e);
            }
        }
    }

    private void openWriter(
            final long newBlockNumber,
            @NonNull final HashObject lastRunningHash,
            @NonNull final Instant newBlockFirstTransactionConsensusTime) {
        try {
            writer = writerFactory.create();
            writer.init(hapiVersion, lastRunningHash, newBlockFirstTransactionConsensusTime, currentBlockNumber);
        } catch (final Exception e) {
            // This represents an almost certainly fatal error. In the FUTURE we should look at dealing with this in a
            // more comprehensive and consistent way. Maybe we retry a bunch of times before giving up, then restart
            // the node. Or maybe we block forever. Or maybe we disable event intake while we keep trying to get this
            // to work. Or maybe we just shut down the node.
            logger.error("Error creating or initializing a block record writer for block {}", newBlockNumber, e);
            throw e;
        }
    }
}
