// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.pipeline;

import static com.swirlds.base.units.UnitConstants.BYTES_TO_MEBIBYTES;
import static com.swirlds.base.units.UnitConstants.KB_TO_BYTES;
import static com.swirlds.base.units.UnitConstants.MEBIBYTES_TO_BYTES;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.validator.Validator;
import com.hedera.statevalidation.validator.listener.ValidationListener;
import com.hedera.statevalidation.validator.model.DataStats;
import com.hedera.statevalidation.validator.model.DiskDataItem;
import com.hedera.statevalidation.validator.model.DiskDataItem.Type;
import com.hedera.statevalidation.validator.model.ReadSegment;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileReader;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Orchestrates the parallel validation pipeline for MerkleDB data processing.
 *
 * <p>This executor implements a producer-consumer pattern with the following stages:
 * <ol>
 *   <li><b>Segmentation</b> - Partitions data sources into segments for parallel reading</li>
 *   <li><b>Data Reading</b> - IO threads read data from in-memory stores and disk files</li>
 *   <li><b>Processing</b> - CPU threads consume batches from a bounded queue and feed validators</li>
 *   <li><b>Final Validation</b> - Validators perform their final checks after all data is processed</li>
 * </ol>
 *
 * <p>Backpressure is controlled via a bounded {@link java.util.concurrent.BlockingQueue},
 * preventing memory exhaustion when IO outpaces processing.
 *
 * <p>This class is not thread-safe and instances should not be reused. Use the static
 * {@link #run} method for execution.
 */
public final class ValidationPipelineExecutor {

    private static final Logger log = LogManager.getLogger(ValidationPipelineExecutor.class);

    // Configuration parameters
    private final int ioThreads;
    private final int processThreads;
    private final int queueCapacity;
    private final int batchSize;
    private final int minSegmentSizeMib;
    private final int segmentMultiplier;
    private final int bufferSizeKib;

    // Input data
    private final MerkleDbDataSource vds;
    private final Map<Type, Set<Validator>> validators;
    private final Set<ValidationListener> validationListeners;

    // Runtime state (initialized in execute())
    private BlockingQueue<List<DiskDataItem>> dataQueue;
    private DataStats dataStats;
    private AtomicLong totalBoundarySearchTime;

    private ValidationPipelineExecutor(
            @NonNull final MerkleDbDataSource vds,
            @NonNull final Map<Type, Set<Validator>> validators,
            @NonNull final Set<ValidationListener> validationListeners,
            final int ioThreads,
            final int processThreads,
            final int queueCapacity,
            final int batchSize,
            final int minSegmentSizeMib,
            final int segmentMultiplier,
            final int bufferSizeKib) {
        this.vds = vds;
        this.validators = validators;
        this.validationListeners = validationListeners;
        this.ioThreads = ioThreads;
        this.processThreads = processThreads;
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.minSegmentSizeMib = minSegmentSizeMib;
        this.segmentMultiplier = segmentMultiplier;
        this.bufferSizeKib = bufferSizeKib;
    }

    /**
     * Executes the validation pipeline.
     *
     * @param vds the MerkleDB data source
     * @param validators map of validators by data type
     * @param validationListeners listeners for validation events
     * @param ioThreads number of IO threads for reading from disk
     * @param processThreads number of CPU threads for processing segments
     * @param queueCapacity queue capacity for backpressure control
     * @param batchSize batch size for processing items
     * @param minSegmentSizeMib minimum segment size in mebibytes (MiB) for file reading
     * @param segmentMultiplier multiplier for IO threads to determine target number of segments
     * @param bufferSizeKib buffer size in kibibytes (KiB) for file reading operations
     * @return true if pipeline completed successfully without errors, false otherwise
     * @throws InterruptedException if the pipeline was interrupted
     */
    public static boolean run(
            @NonNull final MerkleDbDataSource vds,
            @NonNull final Map<Type, Set<Validator>> validators,
            @NonNull final Set<ValidationListener> validationListeners,
            final int ioThreads,
            final int processThreads,
            final int queueCapacity,
            final int batchSize,
            final int minSegmentSizeMib,
            final int segmentMultiplier,
            final int bufferSizeKib)
            throws InterruptedException {

        return new ValidationPipelineExecutor(
                        vds,
                        validators,
                        validationListeners,
                        ioThreads,
                        processThreads,
                        queueCapacity,
                        batchSize,
                        minSegmentSizeMib,
                        segmentMultiplier,
                        bufferSizeKib)
                .execute();
    }

    private boolean execute() throws InterruptedException {
        try (final ExecutorService ioPool = Executors.newFixedThreadPool(ioThreads)) {
            try (final ExecutorService processPool = Executors.newFixedThreadPool(processThreads)) {

                // Get data file collections
                final DataFileCollection pathToKeyValueDfc =
                        vds.getKeyValueStore().getFileCollection();
                //noinspection DataFlowIssue
                final DataFileCollection idToHashChunksDfc =
                        vds.getHashChunkStore().getFileCollection();
                final DataFileCollection keyToPathDfc = vds.getKeyToPath().getFileCollection();

                // Partition data sources into read segments
                final var readSegments = new ArrayList<ReadSegment>();

                if (validators.containsKey(Type.P2KV)) {
                    readSegments.addAll(partitionIntoSegments(pathToKeyValueDfc, Type.P2KV));
                }
                if (validators.containsKey(Type.ID2C)) {
                    readSegments.addAll(partitionIntoSegments(idToHashChunksDfc, Type.ID2C));
                }
                if (validators.containsKey(Type.K2P)) {
                    readSegments.addAll(partitionIntoSegments(keyToPathDfc, Type.K2P));
                }
                log.debug("Total file read segments: {}", readSegments.size());

                // Sort segments: largest segments first (better thread utilization)
                readSegments.sort((a, b) -> Long.compare(b.endByte() - b.startByte(), a.endByte() - a.startByte()));

                // Initialize data structures for processing
                dataStats = new DataStats();
                totalBoundarySearchTime = new AtomicLong(0L);
                dataQueue = new LinkedBlockingQueue<>(queueCapacity);

                final var processorFutures = new ArrayList<Future<Void>>();
                final var ioFutures = new ArrayList<Future<Void>>();

                // Start process threads
                for (int i = 0; i < processThreads; i++) {
                    processorFutures.add(processPool.submit(
                            new ProcessorTask(validators, validationListeners, dataQueue, vds, dataStats)));
                }

                // Submit read tasks
                for (final ReadSegment segment : readSegments) {
                    ioFutures.add(ioPool.submit(() -> {
                        readFileSegment(segment.reader(), segment.type(), segment.startByte(), segment.endByte());
                        return null;
                    }));
                }

                // Wait for all io tasks to complete
                for (final Future<Void> future : ioFutures) {
                    try {
                        future.get();
                    } catch (final ExecutionException e) {
                        ioPool.shutdownNow();
                        processPool.shutdownNow();
                        throw new RuntimeException("IO Task failed", e.getCause() != null ? e.getCause() : e);
                    }
                }

                // Send one poison pill per processor
                for (int i = 0; i < processThreads; i++) {
                    dataQueue.put(List.of(DiskDataItem.poisonPill()));
                }

                // Wait for all processor tasks to complete
                for (final Future<Void> future : processorFutures) {
                    try {
                        future.get();
                    } catch (final ExecutionException e) {
                        throw new RuntimeException("Processor Task failed", e.getCause() != null ? e.getCause() : e);
                    }
                }

                // Perform final validations
                for (final var validatorSet : validators.values()) {
                    for (final var validator : validatorSet) {
                        try {
                            validator.validate();
                            validationListeners.forEach(
                                    listener -> listener.onValidationCompleted(validator.getName()));
                        } catch (final ValidationException e) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(e));
                        } catch (final Exception e) {
                            validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                    validator.getName(),
                                    "Unexpected exception during validation: " + e.getMessage(),
                                    e)));
                        }
                    }
                }

                // Output only relevant data stats
                if (validators.containsKey(Type.P2KV)) {
                    log.info(
                            "P2KV (Path -> Key/Value) Data Stats: \n {}",
                            dataStats.getP2kv().toStringContent());
                }
                if (validators.containsKey(Type.ID2C)) {
                    log.info(
                            "ID2C (Chunk ID -> Chunk) Data Stats: \n {}",
                            dataStats.getId2c().toStringContent());
                }
                if (validators.containsKey(Type.K2P)) {
                    log.info(
                            "K2P (Key -> Path) Data Stats: \n {}",
                            dataStats.getK2p().toStringContent());
                }

                // Don't log total aggregate stats if only one type of validator is present
                if (validators.size() > 1) {
                    log.info(dataStats);
                }

                // Debug metrics
                log.debug("Total boundary search time: {} ms", totalBoundarySearchTime.get());

                return !dataStats.hasErrorReads();
            }
        }
    }

    /**
     * Partitions a data file collection into read segments for parallel processing.
     * Divides files into segments based on configuration parameters.
     *
     * @param dfc the data file collection to read from
     * @param dataType the type of data items in this collection
     * @return list of file read segments
     */
    private List<ReadSegment> partitionIntoSegments(
            @NonNull final DataFileCollection dfc, @NonNull final Type dataType) {

        final List<ReadSegment> readSegments = new ArrayList<>();

        final long collectionTotalSize = dfc.getAllCompletedFiles().stream()
                .mapToLong(DataFileReader::getSize)
                .sum();

        // Determine segment count and size for each file
        for (final DataFileReader reader : dfc.getAllCompletedFiles()) {
            final long fileSize = reader.getSize();
            if (fileSize == 0) {
                log.warn(
                        "Unexpected empty data file (size 0): {}",
                        reader.getPath().getFileName());
                continue;
            }

            final int segments = calculateOptimalSegments(fileSize, collectionTotalSize);
            final long segmentSize = (fileSize + segments - 1) / segments;

            log.debug(
                    "File: {} size: {} MB, segments: {} segment size: {} MB",
                    reader.getPath().getFileName(),
                    fileSize * BYTES_TO_MEBIBYTES,
                    segments,
                    segmentSize * BYTES_TO_MEBIBYTES);

            // Divide each file into segments for parallel reading
            for (int i = 0; i < segments; i++) {
                final long startByte = i * segmentSize;
                final long endByte = Math.min(startByte + segmentSize, fileSize);

                readSegments.add(new ReadSegment(reader, dataType, startByte, endByte));
            }
        }

        return readSegments;
    }

    /**
     * Calculates the optimal number of segments for a file based on its size and configuration.
     *
     * @param fileSize the size of the file in bytes
     * @param collectionTotalSize the total size of all files in the collection
     * @return the optimal number of segments for the file
     */
    private int calculateOptimalSegments(final long fileSize, final long collectionTotalSize) {
        final int minSegmentSize = minSegmentSizeMib * MEBIBYTES_TO_BYTES;

        // Calculate target segment size: divide total collection size by (ioThreads * segmentMultiplier)
        // to distribute work evenly across threads, but ensure it's at least minSegmentSize
        final long targetSegmentSize = Math.max(collectionTotalSize / (ioThreads * segmentMultiplier), minSegmentSize);

        // If file is smaller than target segment size, process it as a single segment
        if (fileSize < targetSegmentSize) {
            return 1;
        }

        // Otherwise, divide file into segments of approximately targetSegmentSize (round up)
        return (int) Math.ceil((double) fileSize / targetSegmentSize);
    }

    /**
     * Reads a segment of data from a file and puts batches into the queue.
     *
     * @param reader the data file reader
     * @param dataType the type of data items
     * @param startByte the starting byte offset
     * @param endByte the ending byte offset
     * @throws IOException if there was a problem reading from the file
     * @throws InterruptedException if the thread was interrupted while waiting to put into the queue
     */
    private void readFileSegment(
            @NonNull final DataFileReader reader,
            @NonNull final Type dataType,
            final long startByte,
            final long endByte)
            throws IOException, InterruptedException {

        final int bufferSizeBytes = bufferSizeKib * KB_TO_BYTES;
        try (ChunkedFileIterator iterator = new ChunkedFileIterator(
                reader.getPath(),
                reader.getMetadata(),
                vds.getHashChunkHeight(),
                dataType,
                startByte,
                endByte,
                bufferSizeBytes,
                totalBoundarySearchTime)) {

            List<DiskDataItem> batch = new ArrayList<>(batchSize);
            while (iterator.next()) {
                final BufferedData originalData = iterator.getDataItemData();
                final Bytes dataCopy = originalData.getBytes(0, originalData.remaining());

                final DiskDataItem diskDataItem =
                        new DiskDataItem(dataType, dataCopy, iterator.getDataItemDataLocation());
                batch.add(diskDataItem);

                if (batch.size() >= batchSize) {
                    dataQueue.put(batch);
                    batch = new ArrayList<>(batchSize);
                }
            }

            if (!batch.isEmpty()) {
                dataQueue.put(batch);
            }
        }
    }
}
