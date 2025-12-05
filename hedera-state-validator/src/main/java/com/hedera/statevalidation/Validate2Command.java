// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.poc.validator.AccountAndSupplyValidator.ACCOUNT_TAG;
import static com.hedera.statevalidation.poc.validator.EntityIdCountValidator.ENTITY_ID_COUNT_TAG;
import static com.hedera.statevalidation.poc.validator.EntityIdUniquenessValidator.ENTITY_ID_UNIQUENESS_TAG;
import static com.hedera.statevalidation.poc.validator.HashRecordIntegrityValidator.INTERNAL_TAG;
import static com.hedera.statevalidation.poc.validator.HdhmBucketIntegrityValidator.HDHM_TAG;
import static com.hedera.statevalidation.poc.validator.LeafBytesIntegrityValidator.LEAF_TAG;
import static com.hedera.statevalidation.poc.validator.TokenRelationsIntegrityValidator.TOKEN_RELATIONS_TAG;
import static com.swirlds.base.units.UnitConstants.BYTES_TO_MEBIBYTES;
import static com.swirlds.base.units.UnitConstants.MEBIBYTES_TO_BYTES;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.poc.listener.LoggingValidationListener;
import com.hedera.statevalidation.poc.listener.ValidationListener;
import com.hedera.statevalidation.poc.model.DataStats;
import com.hedera.statevalidation.poc.model.ItemData;
import com.hedera.statevalidation.poc.model.ItemData.Type;
import com.hedera.statevalidation.poc.pipeline.ChunkedFileIterator;
import com.hedera.statevalidation.poc.pipeline.ProcessorTask;
import com.hedera.statevalidation.poc.util.ValidationException;
import com.hedera.statevalidation.poc.validator.AccountAndSupplyValidator;
import com.hedera.statevalidation.poc.validator.EntityIdCountValidator;
import com.hedera.statevalidation.poc.validator.EntityIdUniquenessValidator;
import com.hedera.statevalidation.poc.validator.HashRecordIntegrityValidator;
import com.hedera.statevalidation.poc.validator.HdhmBucketIntegrityValidator;
import com.hedera.statevalidation.poc.validator.LeafBytesIntegrityValidator;
import com.hedera.statevalidation.poc.validator.TokenRelationsIntegrityValidator;
import com.hedera.statevalidation.poc.validator.api.Validator;
import com.hedera.statevalidation.util.StateUtils;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.merkledb.files.DataFileReader;
import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@SuppressWarnings("FieldMayBeFinal")
@Command(
        name = "validate2",
        mixinStandardHelpOptions = true,
        description = "Validate command v2. Validates the state by running some of the validators in parallel.")
public class Validate2Command implements Runnable {

    private static final Logger log = LogManager.getLogger(Validate2Command.class);

    @ParentCommand
    private StateOperatorCommand parent;

    @Option(
            names = {"-io", "--io-threads"},
            description = "Number of IO threads for reading from disk.")
    private int ioThreads = 4;

    @Option(
            names = {"-p", "--process-threads"},
            description = "Number of CPU threads for processing chunks.")
    private int processThreads = 6;

    @Option(
            names = {"-q", "--queue-capacity"},
            description = "Queue capacity for backpressure control.")
    private int queueCapacity = 100;

    @Option(
            names = {"-b", "--batch-size"},
            description = "Batch size for processing items.")
    private int batchSize = 10;

    @CommandLine.Parameters(
            arity = "1..*",
            description = "Tag to run: ["
                    + INTERNAL_TAG
                    + ", "
                    + LEAF_TAG
                    + ", "
                    + HDHM_TAG
                    + ", "
                    + ACCOUNT_TAG
                    + ", "
                    + TOKEN_RELATIONS_TAG
                    + ", "
                    + ENTITY_ID_COUNT_TAG
                    + ", "
                    + ENTITY_ID_UNIQUENESS_TAG
                    + "]")
    private String[] tags = {
        INTERNAL_TAG,
        LEAF_TAG,
        HDHM_TAG,
        ACCOUNT_TAG,
        TOKEN_RELATIONS_TAG,
        ENTITY_ID_COUNT_TAG,
        ENTITY_ID_UNIQUENESS_TAG
    };

    private Validate2Command() {}

    @Override
    public void run() {
        try (ExecutorService ioPool = Executors.newFixedThreadPool(ioThreads)) {
            try (ExecutorService processPool = Executors.newFixedThreadPool(processThreads)) {
                final BlockingQueue<List<ItemData>> dataQueue = new LinkedBlockingQueue<>(queueCapacity);

                final long startTime = System.currentTimeMillis();
                final AtomicLong totalBoundarySearchMillis = new AtomicLong(0L);

                // Initialize state and get data file collections
                parent.initializeStateDir();
                final DeserializedSignedState deserializedSignedState = StateUtils.getDeserializedSignedState();
                final MerkleNodeState state =
                        deserializedSignedState.reservedSignedState().get().getState();
                final VirtualMap virtualMap = (VirtualMap) state.getRoot();
                final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

                final DataFileCollection pathToKeyValueDfc =
                        vds.getPathToKeyValue().getFileCollection();
                final DataFileCollection pathToHashDfc = vds.getHashStoreDisk().getFileCollection();
                final DataFileCollection keyToPathDfc = vds.getKeyToPath().getFileCollection();

                // Initialize validators and listeners
                final List<ValidationListener> validationListeners = List.of(new LoggingValidationListener());
                final Map<Type, CopyOnWriteArraySet<Validator>> validators =
                        createAndInitValidators(state, tags, validationListeners);

                int totalFiles = 0;
                long globalTotalSize = 0L;
                final var fileReadTasks = new ArrayList<FileReadTask>();

                if (validators.containsKey(Type.P2KV)) {
                    totalFiles += pathToKeyValueDfc.getAllCompletedFiles().size();
                    globalTotalSize += pathToKeyValueDfc.getAllCompletedFiles().stream()
                            .mapToLong(DataFileReader::getSize)
                            .sum();
                    log.debug(
                            "P2KV file count: {}",
                            pathToKeyValueDfc.getAllCompletedFiles().size());
                }
                if (validators.containsKey(Type.P2H)) {
                    totalFiles += pathToHashDfc.getAllCompletedFiles().size();
                    globalTotalSize += pathToHashDfc.getAllCompletedFiles().stream()
                            .mapToLong(DataFileReader::getSize)
                            .sum();
                    log.debug(
                            "P2H file count: {}",
                            pathToHashDfc.getAllCompletedFiles().size());
                }
                if (validators.containsKey(Type.K2P)) {
                    totalFiles += keyToPathDfc.getAllCompletedFiles().size();
                    globalTotalSize += keyToPathDfc.getAllCompletedFiles().stream()
                            .mapToLong(DataFileReader::getSize)
                            .sum();
                    log.debug(
                            "K2P file count: {}",
                            keyToPathDfc.getAllCompletedFiles().size());
                }

                // Plan all file read tasks (calculate chunks for each file)
                if (validators.containsKey(Type.P2KV)) {
                    fileReadTasks.addAll(planTasksFor(pathToKeyValueDfc, Type.P2KV, ioThreads, globalTotalSize));
                }
                if (validators.containsKey(Type.P2H)) {
                    fileReadTasks.addAll(planTasksFor(pathToHashDfc, Type.P2H, ioThreads, globalTotalSize));
                }
                if (validators.containsKey(Type.K2P)) {
                    fileReadTasks.addAll(planTasksFor(keyToPathDfc, Type.K2P, ioThreads, globalTotalSize));
                }

                log.debug("File count: {}", totalFiles);
                log.debug("Total data size: {} MB", globalTotalSize * BYTES_TO_MEBIBYTES);

                // Sort tasks: largest chunks first (better thread utilization)
                fileReadTasks.sort((a, b) -> Long.compare(b.endByte - b.startByte, a.endByte - a.startByte));

                final int totalFileReadTasks = fileReadTasks.size();

                log.debug("Total file read tasks: {}", totalFileReadTasks);

                final DataStats dataStats = new DataStats();

                final List<Future<Void>> processorFutures = new ArrayList<>();
                final List<Future<Void>> ioFutures = new ArrayList<>();

                // Start processor threads
                for (int i = 0; i < processThreads; i++) {
                    processorFutures.add(processPool.submit(
                            new ProcessorTask(validators, validationListeners, dataQueue, vds, dataStats)));
                }

                // Submit all planned file read tasks
                for (final FileReadTask task : fileReadTasks) {
                    ioFutures.add(ioPool.submit(() -> {
                        readFileChunk(
                                task.reader,
                                dataQueue,
                                task.type,
                                task.startByte,
                                task.endByte,
                                totalBoundarySearchMillis);
                        return null;
                    }));
                }

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
                    dataQueue.put(List.of(ItemData.poisonPill()));
                }

                for (final Future<Void> future : processorFutures) {
                    try {
                        future.get();
                    } catch (final ExecutionException e) {
                        throw new RuntimeException("Processor Task failed", e.getCause() != null ? e.getCause() : e);
                    }
                }

                boolean anyValidationFailed = false;
                for (var validatorSet : validators.values()) {
                    for (var validator : validatorSet) {
                        try {
                            validator.validate();
                            validationListeners.forEach(listener -> listener.onValidationCompleted(validator.getTag()));
                        } catch (final ValidationException e) {
                            anyValidationFailed = true;
                            validationListeners.forEach(listener -> listener.onValidationFailed(e));
                        } catch (final Exception e) {
                            anyValidationFailed = true;
                            validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                                    validator.getTag(),
                                    "Unexpected exception during validation: " + e.getMessage(),
                                    e)));
                        }
                    }
                }

                if (validators.containsKey(Type.P2KV)) {
                    log.info(
                            "P2KV (Path -> Key/Value) Data Stats: \n {}",
                            dataStats.getP2kv().toStringContent());
                }
                if (validators.containsKey(Type.P2H)) {
                    log.info(
                            "P2H (Path -> Hash) Data Stats: \n {}",
                            dataStats.getP2h().toStringContent());
                }
                if (validators.containsKey(Type.K2P)) {
                    log.info(
                            "K2P (Key -> Path) Data Stats: \n {}",
                            dataStats.getK2p().toStringContent());
                }

                log.info(dataStats);

                // common validation for error reads
                if (dataStats.hasErrorReads()) {
                    throw new RuntimeException("Error reads found. Full info: \n " + dataStats);
                }

                if (anyValidationFailed) {
                    throw new ValidationException("*", "One or more validators failed. Check logs for details.");
                }

                log.debug("Total boundary search time: {} ms", totalBoundarySearchMillis.get());
                log.debug("Total processing time: {} ms", System.currentTimeMillis() - startTime);
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Validation interrupted", e);
        } catch (final Exception e) {
            throw new IllegalStateException("Validation failed unexpectedly", e);
        }
    }

    private Map<Type, CopyOnWriteArraySet<Validator>> createAndInitValidators(
            @NonNull final MerkleNodeState state,
            @NonNull final String[] tags,
            @NonNull final List<ValidationListener> validationListeners) {
        final Set<String> tagSet = Set.of(tags);

        final Map<Type, CopyOnWriteArraySet<Validator>> validatorsMap = new HashMap<>();

        // 1. Populate map with validators that match supplied tags
        final var hashRecordValidators = new CopyOnWriteArraySet<Validator>();
        final var hashRecordValidator = new HashRecordIntegrityValidator();
        if (tagSet.contains(hashRecordValidator.getTag())) {
            hashRecordValidators.add(hashRecordValidator);
        }
        if (!hashRecordValidators.isEmpty()) {
            validatorsMap.put(Type.P2H, hashRecordValidators);
        }
        // hdhm
        final var hdhmBucketValidators = new CopyOnWriteArraySet<Validator>();
        final var hdhmBucketValidator = new HdhmBucketIntegrityValidator();
        if (tagSet.contains(hdhmBucketValidator.getTag())) {
            hdhmBucketValidators.add(hdhmBucketValidator);
        }
        if (!hdhmBucketValidators.isEmpty()) {
            validatorsMap.put(Type.K2P, hdhmBucketValidators);
        }
        // leaf, etc.
        final var leafBytesValidators = new CopyOnWriteArraySet<Validator>();
        final var leafBytesValidator = new LeafBytesIntegrityValidator();
        if (tagSet.contains(leafBytesValidator.getTag())) {
            leafBytesValidators.add(leafBytesValidator);
        }
        final var accountValidator = new AccountAndSupplyValidator();
        if (tagSet.contains(accountValidator.getTag())) {
            leafBytesValidators.add(accountValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final var tokenRelationsValidator = new TokenRelationsIntegrityValidator();
        if (tagSet.contains(tokenRelationsValidator.getTag())) {
            leafBytesValidators.add(tokenRelationsValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final var entityIdCountValidator = new EntityIdCountValidator();
        if (tagSet.contains(entityIdCountValidator.getTag())) {
            leafBytesValidators.add(entityIdCountValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final var entityIdUniquenessValidator = new EntityIdUniquenessValidator();
        if (tagSet.contains(entityIdUniquenessValidator.getTag())) {
            leafBytesValidators.add(entityIdUniquenessValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }

        // 2. Initialize validators and remove if initialization fails
        validatorsMap.values().removeIf(validatorSet -> {
            validatorSet.removeIf(validator -> {
                validationListeners.forEach(listener -> listener.onValidationStarted(validator.getTag()));
                try {
                    validator.initialize(state);
                    return false; // keep validator
                } catch (final Exception e) {
                    validationListeners.forEach(listener -> listener.onValidationFailed(
                            new ValidationException(validator.getTag(), "Unexpected exception: " + e.getMessage(), e)));
                    return true; // remove validator
                }
            });
            return validatorSet.isEmpty(); // remove entry if no validators remain
        });

        // 3. Return the fully initialized and cleaned map
        return validatorsMap;
    }

    // Helper: Plan tasks for one collection
    private List<FileReadTask> planTasksFor(
            @NonNull final DataFileCollection dfc,
            @NonNull final ItemData.Type dataType,
            final int ioThreads,
            final long globalTotalSize) {

        final List<FileReadTask> tasks = new ArrayList<>();

        final long collectionTotalSize = dfc.getAllCompletedFiles().stream()
                .mapToLong(DataFileReader::getSize)
                .sum();

        for (final DataFileReader reader : dfc.getAllCompletedFiles()) {
            final long fileSize = reader.getSize();
            if (fileSize == 0) {
                continue;
            }

            final int chunks = calculateOptimalChunks(reader, ioThreads, collectionTotalSize);
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

                if (startByte >= fileSize) {
                    continue;
                }

                tasks.add(new FileReadTask(reader, dataType, startByte, endByte));
            }
        }

        return tasks;
    }

    private int calculateOptimalChunks(
            @NonNull final DataFileReader reader, final int ioThreads, final long globalTotalDataSize) {

        final long fileSize = reader.getSize();

        // literals here can be extracted to params
        final long targetChunkSize = Math.max(globalTotalDataSize / (ioThreads * 2), 128 * MEBIBYTES_TO_BYTES);

        if (fileSize < targetChunkSize) {
            return 1;
        }

        return (int) Math.ceil((double) fileSize / targetChunkSize);
    }

    private void readFileChunk(
            @NonNull final DataFileReader reader,
            @NonNull final BlockingQueue<List<ItemData>> dataQueue,
            @NonNull final Type dataType,
            final long startByte,
            final long endByte,
            @NonNull final AtomicLong totalBoundarySearchMillis)
            throws IOException, InterruptedException {

        try (ChunkedFileIterator iterator = new ChunkedFileIterator(
                reader.getPath(), reader.getMetadata(), dataType, startByte, endByte, totalBoundarySearchMillis)) {

            List<ItemData> batch = new ArrayList<>(batchSize);
            while (iterator.next()) {
                final BufferedData originalData = iterator.getDataItemData();
                final Bytes dataCopy = originalData.getBytes(0, originalData.remaining());

                final ItemData itemData = new ItemData(dataType, dataCopy, iterator.getDataItemDataLocation());
                batch.add(itemData);

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

    // Helper record to hold task information
    private record FileReadTask(DataFileReader reader, ItemData.Type type, long startByte, long endByte) {}
}
