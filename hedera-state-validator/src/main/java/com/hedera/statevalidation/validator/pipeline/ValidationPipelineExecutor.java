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
import com.hedera.statevalidation.validator.model.FileReadTaskConfig;
import com.hedera.statevalidation.validator.model.MemoryHashItem;
import com.hedera.statevalidation.validator.model.MemoryReadTaskConfig;
import com.hedera.statevalidation.validator.model.ValidationItem;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.HashList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.virtualmap.datasource.VirtualHashRecord;
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
import org.hiero.base.crypto.Hash;

/**
 * Orchestrates the parallel validation pipeline for MerkleDB data processing.
 *
 * <p>This executor implements a producer-consumer pattern with the following stages:
 * <ol>
 *   <li><b>Task Planning</b> - Divides data files into chunks for parallel reading</li>
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
    private final int minChunkSizeMib;
    private final int chunkMultiplier;
    private final int bufferSizeKib;

    // Input data
    private final MerkleDbDataSource vds;
    private final Map<Type, Set<Validator>> validators;
    private final Set<ValidationListener> validationListeners;

    // Runtime state (initialized in execute())
    private HashList pathToHashRam;
    private BlockingQueue<List<ValidationItem>> dataQueue;
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
            final int minChunkSizeMib,
            final int chunkMultiplier,
            final int bufferSizeKib) {
        this.vds = vds;
        this.validators = validators;
        this.validationListeners = validationListeners;
        this.ioThreads = ioThreads;
        this.processThreads = processThreads;
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.minChunkSizeMib = minChunkSizeMib;
        this.chunkMultiplier = chunkMultiplier;
        this.bufferSizeKib = bufferSizeKib;
    }

    /**
     * Executes the validation pipeline.
     *
     * @param vds the MerkleDB data source
     * @param validators map of validators by data type
     * @param validationListeners listeners for validation events
     * @param ioThreads number of IO threads for reading from disk
     * @param processThreads number of CPU threads for processing chunks
     * @param queueCapacity queue capacity for backpressure control
     * @param batchSize batch size for processing items
     * @param minChunkSizeMib minimum chunk size in mebibytes (MiB) for file reading
     * @param chunkMultiplier multiplier for IO threads to determine target number of chunks
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
            final int minChunkSizeMib,
            final int chunkMultiplier,
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
                        minChunkSizeMib,
                        chunkMultiplier,
                        bufferSizeKib)
                .execute();
    }

    private boolean execute() throws InterruptedException {
        try (final ExecutorService ioPool = Executors.newFixedThreadPool(ioThreads)) {
            try (final ExecutorService processPool = Executors.newFixedThreadPool(processThreads)) {

                // Get data file collections
                final DataFileCollection pathToKeyValueDfc =
                        vds.getPathToKeyValue().getFileCollection();
                //noinspection DataFlowIssue
                final DataFileCollection pathToHashDfc = vds.getHashStoreDisk().getFileCollection();
                final DataFileCollection keyToPathDfc = vds.getKeyToPath().getFileCollection();
                // Get in-memory hash store (may be null)
                pathToHashRam = vds.getHashStoreRam();
                final long inMemoryHashThreshold = pathToHashRam != null ? vds.getHashesRamToDiskThreshold() : 0;

                // Plan read tasks
                final var fileReadTasks = new ArrayList<FileReadTaskConfig>();
                final var memoryReadTasks = new ArrayList<MemoryReadTaskConfig>();

                if (validators.containsKey(Type.P2KV)) {
                    fileReadTasks.addAll(planFileReadTasks(pathToKeyValueDfc, Type.P2KV));
                }
                if (validators.containsKey(Type.P2H)) {
                    fileReadTasks.addAll(planFileReadTasks(pathToHashDfc, Type.P2H));

                    // Submit in-memory hash read tasks if memory store is available
                    if (pathToHashRam != null) {
                        final long lastLeafPath = vds.getLastLeafPath();
                        final long memoryEndPathExc = Math.min(inMemoryHashThreshold, lastLeafPath + 1);
                        if (memoryEndPathExc > 0) {
                            memoryReadTasks.addAll(planP2HMemoryReadTasks(memoryEndPathExc));
                            log.debug(
                                    "In-memory P2H read tasks: {}, range: [0, {})",
                                    memoryReadTasks.size(),
                                    memoryEndPathExc);
                        }
                    }
                }
                if (validators.containsKey(Type.K2P)) {
                    fileReadTasks.addAll(planFileReadTasks(keyToPathDfc, Type.K2P));
                }
                log.debug("Total file read tasks: {}", fileReadTasks.size());

                // Sort tasks: largest chunks first (better thread utilization)
                fileReadTasks.sort((a, b) -> Long.compare(b.endByte() - b.startByte(), a.endByte() - a.startByte()));

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
                for (final MemoryReadTaskConfig task : memoryReadTasks) {
                    ioFutures.add(ioPool.submit(() -> {
                        readInMemoryHashChunk(task.startPath(), task.endPath());
                        return null;
                    }));
                }
                for (final FileReadTaskConfig task : fileReadTasks) {
                    ioFutures.add(ioPool.submit(() -> {
                        readFileChunk(task.reader(), task.type(), task.startByte(), task.endByte());
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
                if (validators.containsKey(Type.P2H)) {
                    log.info(
                            "P2H (Path -> Hash) Data Stats: \n {}",
                            dataStats.getP2h().toStringContent());
                    if (pathToHashRam != null && dataStats.getP2hMemory().getItemCount() > 0) {
                        log.info(
                                "P2H (Path -> Hash) Memory Data Stats: \n  Items: {}",
                                dataStats.getP2hMemory().getItemCount());
                    }
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

    // ==================== Task Planning Methods ====================

    /**
     * Plans file read tasks for a data file collection.
     * Divides files into chunks based on configuration parameters for parallel processing.
     *
     * @param dfc the data file collection to read from
     * @param dataType the type of data items in this collection
     * @return list of file read tasks
     */
    private List<FileReadTaskConfig> planFileReadTasks(
            @NonNull final DataFileCollection dfc, @NonNull final Type dataType) {

        final List<FileReadTaskConfig> tasks = new ArrayList<>();

        final long collectionTotalSize = dfc.getAllCompletedFiles().stream()
                .mapToLong(DataFileReader::getSize)
                .sum();

        // Calculate chunks for each file
        for (final DataFileReader reader : dfc.getAllCompletedFiles()) {
            final long fileSize = reader.getSize();
            if (fileSize == 0) {
                log.warn(
                        "Unexpected empty data file (size 0): {}",
                        reader.getPath().getFileName());
                continue;
            }

            final int chunks = calculateOptimalChunks(fileSize, collectionTotalSize);
            final long chunkSize = (fileSize + chunks - 1) / chunks;

            log.debug(
                    "File: {} size: {} MB, chunks: {} chunkSize: {} MB",
                    reader.getPath().getFileName(),
                    fileSize * BYTES_TO_MEBIBYTES,
                    chunks,
                    chunkSize * BYTES_TO_MEBIBYTES);

            // Create tasks for each chunk
            for (int i = 0; i < chunks; i++) {
                final long startByte = i * chunkSize;
                final long endByte = Math.min(startByte + chunkSize, fileSize);

                tasks.add(new FileReadTaskConfig(reader, dataType, startByte, endByte));
            }
        }

        return tasks;
    }

    /**
     * Calculates the optimal number of chunks for a file based on its size and configuration.
     *
     * @param fileSize the size of the file in bytes
     * @param collectionTotalSize the total size of all files in the collection
     * @return the optimal number of chunks for the file
     */
    private int calculateOptimalChunks(final long fileSize, final long collectionTotalSize) {
        final int minChunkSize = minChunkSizeMib * MEBIBYTES_TO_BYTES;

        // Calculate target chunk size: divide total collection size by (ioThreads * chunkMultiplier)
        // to distribute work evenly across threads, but ensure it's at least minChunkSize
        final long targetChunkSize = Math.max(collectionTotalSize / (ioThreads * chunkMultiplier), minChunkSize);

        // If file is smaller than target chunk size, process it as a single chunk
        if (fileSize < targetChunkSize) {
            return 1;
        }

        // Otherwise, divide file into chunks of approximately targetChunkSize (round up)
        return (int) Math.ceil((double) fileSize / targetChunkSize);
    }

    /**
     * Plans in-memory read tasks for P2H (Path to Hash) data.
     * Chunks the path range for parallel processing.
     *
     * @param totalPaths the total number of paths to read
     * @return list of memory read tasks
     */
    private List<MemoryReadTaskConfig> planP2HMemoryReadTasks(final long totalPaths) {
        final List<MemoryReadTaskConfig> tasks = new ArrayList<>();

        // Use a reasonable min chunk size
        final long minPathsPerChunk = 100_000L;
        final int targetChunks = Math.max(1, ioThreads * chunkMultiplier);
        final long pathsPerChunk = Math.max(minPathsPerChunk, (totalPaths + targetChunks - 1) / targetChunks);

        for (long start = 0; start < totalPaths; start += pathsPerChunk) {
            final long end = Math.min(start + pathsPerChunk, totalPaths);
            tasks.add(new MemoryReadTaskConfig(start, end));
        }

        return tasks;
    }

    // ==================== Task Reading Methods ====================

    /**
     * Reads a chunk of data from a file and puts batches into the queue.
     *
     * @param reader the data file reader
     * @param dataType the type of data items
     * @param startByte the starting byte offset
     * @param endByte the ending byte offset
     * @throws IOException if there was a problem reading from the file
     * @throws InterruptedException if the thread was interrupted while waiting to put into the queue
     */
    private void readFileChunk(
            @NonNull final DataFileReader reader,
            @NonNull final Type dataType,
            final long startByte,
            final long endByte)
            throws IOException, InterruptedException {

        final int bufferSizeBytes = bufferSizeKib * KB_TO_BYTES;
        try (ChunkedFileIterator iterator = new ChunkedFileIterator(
                reader.getPath(),
                reader.getMetadata(),
                dataType,
                startByte,
                endByte,
                bufferSizeBytes,
                totalBoundarySearchTime)) {

            List<ValidationItem> batch = new ArrayList<>(batchSize);
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

    /**
     * Reads in-memory hashes for a range of paths and puts batches into the queue.
     *
     * @param startPath the starting path (inclusive)
     * @param endPath the ending path (exclusive)
     * @throws InterruptedException if the thread was interrupted while waiting to put into the queue
     */
    private void readInMemoryHashChunk(final long startPath, final long endPath) throws InterruptedException {
        List<ValidationItem> batch = new ArrayList<>(batchSize);

        for (long path = startPath; path < endPath; path++) {
            try {
                final Hash hash = pathToHashRam.get(path);
                if (hash == null) {
                    log.error("Hash is null when read from memory at path: {}", path);
                    dataStats.getP2hMemory().incrementInvalidLocationCount();
                    continue;
                }

                final VirtualHashRecord record = new VirtualHashRecord(path, hash);
                batch.add(new MemoryHashItem(record));

                if (batch.size() >= batchSize) {
                    dataQueue.put(batch);
                    batch = new ArrayList<>(batchSize);
                }
            } catch (final IOException e) {
                log.error("Failed to read hash from memory at path {}: {}", path, e.getMessage());
                dataStats.getP2hMemory().incrementParseErrorCount();
            }
        }

        if (!batch.isEmpty()) {
            dataQueue.put(batch);
        }
    }
}
