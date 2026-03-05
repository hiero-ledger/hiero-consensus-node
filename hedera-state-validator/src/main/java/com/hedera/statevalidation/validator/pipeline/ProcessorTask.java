// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.pipeline;

import static com.swirlds.merkledb.collections.LongList.IMPERMISSIBLE_VALUE;

import com.hedera.statevalidation.util.LogUtils;
import com.hedera.statevalidation.validator.HashRecordValidator;
import com.hedera.statevalidation.validator.HdhmBucketValidator;
import com.hedera.statevalidation.validator.LeafBytesValidator;
import com.hedera.statevalidation.validator.Validator;
import com.hedera.statevalidation.validator.listener.ValidationListener;
import com.hedera.statevalidation.validator.model.DataStats;
import com.hedera.statevalidation.validator.model.DiskDataItem;
import com.hedera.statevalidation.validator.model.DiskDataItem.Type;
import com.hedera.statevalidation.validator.model.MemoryHashItem;
import com.hedera.statevalidation.validator.model.ValidationItem;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCommon;
import com.swirlds.merkledb.files.hashmap.ParsedBucket;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A concurrent task that processes batches of data items from a blocking queue and dispatches them
 * to appropriate validators based on data type.
 *
 * <p>This class is designed to run as part of a parallel validation pipeline where multiple
 * {@code ProcessorTask} instances consume data items from a shared queue. Each task processes
 * three types of MerkleDB data:
 * <ul>
 *     <li><b>P2KV (Path to Key/Value)</b> - Virtual leaf bytes processed by {@link LeafBytesValidator}</li>
 *     <li><b>P2H (Path to Hash)</b> - Virtual hash records processed by {@link HashRecordValidator}</li>
 *     <li><b>K2P (Key to Path)</b> - HDHM buckets processed by {@link HdhmBucketValidator}</li>
 * </ul>
 *
 * <p>The processor validates each data item's location against the corresponding index to determine
 * if it represents a live object or obsolete data. Live objects are passed to registered validators,
 * while obsolete items are tracked in statistics.
 *
 * <p>The task terminates gracefully when it receives a poison pill item in the queue.
 *
 * @see Validator
 * @see ValidationListener
 * @see DataStats
 */
public class ProcessorTask implements Callable<Void> {

    private static final Logger log = LogManager.getLogger(ProcessorTask.class);

    /** Set of listeners to notify about validation failure in this context */
    private final Set<ValidationListener> validationListeners;

    /** Set of validators for P2KV (Path to Key/Value) data items; may be null if no P2KV validators configured */
    private final Set<Validator> p2kvValidators;
    /** Set of validators for P2H (Path to Hash) data items; may be null if no P2H validators configured */
    private final Set<Validator> p2hValidators;
    /** Set of validators for K2P (Key to Path) data items; may be null if no K2P validators configured */
    private final Set<Validator> k2pValidators;

    /** The MerkleDB data source providing access to file collections for error logging */
    private final MerkleDbDataSource vds;

    /** Blocking queue from which batches of validation items are consumed for processing */
    private final BlockingQueue<List<ValidationItem>> dataQueue;

    /** Index mapping leaf node paths to their disk locations, used to determine if P2KV items are live */
    private final LongList pathToDiskLocationLeafNodes;
    /** Index mapping internal node paths to their disk locations, used to determine if P2H items are live */
    private final LongList pathToDiskLocationInternalNodes;
    /** Index mapping bucket indexes to their disk locations, used to determine if K2P items are live */
    private final LongList bucketIndexToBucketLocation;

    /** Statistics collector for tracking item counts, space usage, and error counts per data type */
    private final DataStats dataStats;

    /**
     * Creates a new ProcessorTask that consumes data items from a queue and dispatches them to validators.
     *
     * @param validators map of data types to their corresponding validator sets;
     * @param validationListeners listeners to notify about validation lifecycle events
     * @param dataQueue the blocking queue from which batches of data items are consumed
     * @param vds the MerkleDB data source providing location indexes and file collections
     * @param dataStats statistics collector for tracking processing metrics
     */
    public ProcessorTask(
            @NonNull final Map<Type, Set<Validator>> validators,
            @NonNull final Set<ValidationListener> validationListeners,
            @NonNull final BlockingQueue<List<ValidationItem>> dataQueue,
            @NonNull final MerkleDbDataSource vds,
            @NonNull final DataStats dataStats) {
        this.validationListeners = validationListeners;

        this.p2kvValidators = validators.get(Type.P2KV);
        this.p2hValidators = validators.get(Type.P2H);
        this.k2pValidators = validators.get(Type.K2P);

        this.dataQueue = dataQueue;

        this.vds = vds;

        this.pathToDiskLocationLeafNodes = vds.getPathToDiskLocationLeafNodes();
        this.pathToDiskLocationInternalNodes = vds.getPathToDiskLocationInternalNodes();
        this.bucketIndexToBucketLocation = (LongList) vds.getKeyToPath().getBucketIndexToBucketLocation();

        this.dataStats = dataStats;
    }

    /**
     * Executes the processor task, continuously consuming and processing batches of data items
     * from the queue until a poison pill is received or the thread is interrupted.
     *
     * <p>Each batch is processed sequentially, with individual items dispatched to the appropriate
     * processing method based on their data type. The task terminates gracefully when it encounters
     * a poison pill item in any batch.
     *
     * @return always returns {@code null} upon completion
     */
    @Override
    public Void call() {
        try {
            while (true) {
                final List<ValidationItem> batch = dataQueue.take();
                boolean stop = false;

                for (final ValidationItem item : batch) {
                    if (item.isPoisonPill()) {
                        stop = true;
                        break;
                    }

                    processItem(item);
                }

                if (stop) {
                    break;
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Processor task interrupted.");
        }
        return null;
    }

    /**
     * Dispatches a single validation item to the appropriate processing method based on its type.
     *
     * <p>Uses pattern matching on the sealed {@link ValidationItem} interface to route items:
     * <ul>
     *     <li>{@link MemoryHashItem} - routed to {@link #processMemoryHashRecord(VirtualHashRecord)}</li>
     *     <li>{@link DiskDataItem} - routed based on {@link DiskDataItem.Type} to the corresponding processor</li>
     * </ul>
     *
     * @param item the validation item to process; must not be a poison pill (checked before calling)
     */
    private void processItem(@NonNull final ValidationItem item) {
        switch (item) {
            case MemoryHashItem mem -> processMemoryHashRecord(mem.hashRecord());
            case DiskDataItem data -> {
                switch (data.type()) {
                    case P2KV -> processVirtualLeafBytes(data);
                    case P2H -> processVirtualHashRecord(data);
                    case K2P -> processBucket(data);
                    case TERMINATOR -> {} // handled before this point
                }
            }
        }
    }

    /**
     * Processes a P2H (Path to Hash) record sourced from in-memory storage.
     *
     * <p>Memory-sourced items are always considered live—no liveness check against the disk location
     * index is performed.
     *
     * @param hashRecord the pre-parsed hash record containing path and hash data from memory
     */
    private void processMemoryHashRecord(@NonNull final VirtualHashRecord hashRecord) {
        dataStats.getP2hMemory().incrementItemCount();

        // Cross-check with a threshold
        if (hashRecord.path() >= vds.getHashesRamToDiskThreshold()) {
            dataStats.getP2hMemory().incrementInvalidLocationCount();
            log.error(
                    "Path found in P2H memory (p:{}) exceeds hashes ram to disc threshold (p:{})",
                    hashRecord.path(),
                    vds.getHashesRamToDiskThreshold());
            return;
        }

        // Cross-check with P2H disk
        if (pathToDiskLocationInternalNodes.get(hashRecord.path()) != IMPERMISSIBLE_VALUE) {
            log.error(
                    "Path found in P2H memory (p:{}) index AND on disk (p:{})",
                    hashRecord.path(),
                    pathToDiskLocationInternalNodes.get(hashRecord.path()));
            dataStats.getP2hMemory().incrementInvalidLocationCount();
        }

        if (p2hValidators == null) {
            return;
        }

        p2hValidators.forEach(validator -> {
            try {
                ((HashRecordValidator) validator).processHashRecord(hashRecord);
            } catch (final Exception e) {
                validationListeners.forEach(listener -> listener.onValidationFailed(
                        new ValidationException(validator.getName(), "Unexpected exception: " + e.getMessage(), e)));
            }
        });
    }

    /**
     * Processes a P2KV (Path to Key/Value) data item containing virtual leaf bytes.
     *
     * <p>This method performs the following operations:
     * <ol>
     *     <li>Updates space and item count statistics</li>
     *     <li>For live items, passes them to all registered P2KV validators</li>
     * </ol>
     *
     * @param data the P2KV data item to process
     */
    private void processVirtualLeafBytes(@NonNull final DiskDataItem data) {
        try {
            dataStats.getP2kv().addSpaceSize(data.bytes().length());
            dataStats.getP2kv().incrementItemCount();

            final int fileIndex = DataFileCommon.fileIndexFromDataLocation(data.location());
            final VirtualLeafBytes<?> virtualLeafBytes =
                    VirtualLeafBytes.parseFrom(data.bytes().toReadableSequentialData());

            if (data.location() == pathToDiskLocationLeafNodes.get(virtualLeafBytes.path())) {
                // Live object, perform ops on it...
                if (p2kvValidators == null) {
                    return;
                }
                p2kvValidators.forEach(validator -> {
                    try {
                        ((LeafBytesValidator) validator).processLeafBytes(data.location(), virtualLeafBytes);
                    } catch (final Exception e) {
                        validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                validator.getName(), "Unexpected exception: " + e.getMessage(), e)));
                    }
                });
            } else if (data.location() < fileIndex) {
                dataStats.getP2kv().incrementInvalidLocationCount();
                LogUtils.printFileDataLocationError(log, "data.location() was invalid for P2KV entry", data.location());
            } else {
                // Add to wasted items/space
                dataStats.getP2kv().addObsoleteSpaceSize(data.bytes().length());
                dataStats.getP2kv().incrementObsoleteItemCount();
            }
        } catch (final Exception e) {
            dataStats.getP2kv().incrementParseErrorCount();
            LogUtils.printFileDataLocationError(log, e.getMessage(), data);
        }
    }

    /**
     * Processes a P2H (Path to Hash) data item containing a virtual hash record.
     *
     * <p>This method performs the following operations:
     * <ol>
     *     <li>Updates space and item count statistics</li>
     *     <li>For live items, passes them to all registered P2H validators</li>
     * </ol>
     *
     * @param data the P2H data item to process
     */
    private void processVirtualHashRecord(@NonNull final DiskDataItem data) {
        try {
            dataStats.getP2h().addSpaceSize(data.bytes().length());
            dataStats.getP2h().incrementItemCount();

            final int fileIndex = DataFileCommon.fileIndexFromDataLocation(data.location());
            final VirtualHashRecord virtualHashRecord =
                    VirtualHashRecord.parseFrom(data.bytes().toReadableSequentialData());
            final long path = virtualHashRecord.path();

            // Index sanity check
            if (path >= 0 && path <= vds.getLastLeafPath() && pathToDiskLocationInternalNodes.get(path) < fileIndex) {
                dataStats.getP2h().incrementInvalidLocationCount();
                LogUtils.printFileDataLocationError(log, "data.location() was invalid for P2H entry", data.location());
                return;
            }

            if (data.location() == pathToDiskLocationInternalNodes.get(path)) {
                // Live object, perform ops on it...
                if (p2hValidators == null) {
                    return;
                }
                p2hValidators.forEach(validator -> {
                    try {
                        ((HashRecordValidator) validator).processHashRecord(virtualHashRecord);
                    } catch (final Exception e) {
                        validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                validator.getName(), "Unexpected exception: " + e.getMessage(), e)));
                    }
                });
            } else if (data.location() < fileIndex) {
                dataStats.getP2h().incrementInvalidLocationCount();
                LogUtils.printFileDataLocationError(log, "data.location() was invalid for P2H entry", data.location());
            } else {
                // Add to wasted items/space
                dataStats.getP2h().addObsoleteSpaceSize(data.bytes().length());
                dataStats.getP2h().incrementObsoleteItemCount();
            }
        } catch (final Exception e) {
            dataStats.getP2h().incrementParseErrorCount();
            LogUtils.printFileDataLocationError(log, e.getMessage(), data);
        }
    }

    /**
     * Processes a K2P (Key to Path) data item containing an HDHM bucket.
     *
     * <p>This method performs the following operations:
     * <ol>
     *     <li>Updates space and item count statistics</li>
     *     <li>For live items, passes them to all registered K2P validators</li>
     * </ol>
     *
     * @param data the K2P data item to process
     */
    private void processBucket(@NonNull final DiskDataItem data) {
        try {
            dataStats.getK2p().addSpaceSize(data.bytes().length());
            dataStats.getK2p().incrementItemCount();

            final int fileIndex = DataFileCommon.fileIndexFromDataLocation(data.location());

            try (final ParsedBucket bucket = new ParsedBucket()) {
                bucket.readFrom(data.bytes().toReadableSequentialData());

                if (data.location() == bucketIndexToBucketLocation.get(bucket.getBucketIndex())) {
                    // Live object, perform ops on it...
                    if (k2pValidators == null) {
                        return;
                    }
                    k2pValidators.forEach(validator -> {
                        try {
                            ((HdhmBucketValidator) validator).processBucket(data.location(), bucket);
                        } catch (final Exception e) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                    validator.getName(), "Unexpected exception: " + e.getMessage(), e)));
                        }
                    });
                } else if (data.location() < fileIndex) {
                    dataStats.getK2p().incrementInvalidLocationCount();
                    LogUtils.printFileDataLocationError(
                            log, "data.location() was invalid for K2P entry", data.location());
                } else {
                    // Add to wasted items/space
                    dataStats.getK2p().addObsoleteSpaceSize(data.bytes().length());
                    dataStats.getK2p().incrementObsoleteItemCount();
                }
            }
        } catch (final Exception e) {
            dataStats.getK2p().incrementParseErrorCount();
            LogUtils.printFileDataLocationError(log, e.getMessage(), data);
        }
    }
}
