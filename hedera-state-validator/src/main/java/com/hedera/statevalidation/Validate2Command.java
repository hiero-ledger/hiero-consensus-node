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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
    private int queueCapacity = 1000;

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
        try {
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
                    final DataFileCollection pathToHashDfc =
                            vds.getHashStoreDisk().getFileCollection();
                    final DataFileCollection keyToPathDfc = vds.getKeyToPath().getFileCollection();

                    // Initialize validators and listeners
                    final List<ValidationListener> validationListeners = List.of(new LoggingValidationListener());
                    final Map<Type, Set<Validator>> validators =
                            createAndInitValidators(state, tags, validationListeners);

                    int totalFiles = 0;
                    long globalTotalSize = 0L;
                    final List<FileReadTask> fileReadTasks = new ArrayList<>();

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

                    final CountDownLatch readerLatch = new CountDownLatch(totalFileReadTasks);
                    final CountDownLatch processorsLatch = new CountDownLatch(processThreads);

                    final DataStats dataStats = new DataStats();

                    // Start processor threads
                    for (int i = 0; i < processThreads; i++) {
                        processPool.submit(new ProcessorTask(
                                validators, validationListeners, dataQueue, vds, dataStats, processorsLatch));
                    }

                    // Submit all planned file read tasks to read file in chunks
                    for (final FileReadTask task : fileReadTasks) {
                        ioPool.submit(() -> {
                            try {
                                readFileChunk(
                                        task.reader,
                                        dataQueue,
                                        task.type,
                                        task.startByte,
                                        task.endByte,
                                        totalBoundarySearchMillis);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                e.printStackTrace(); // TODO: double check this exception
                                throw new RuntimeException("Reader interrupted", e);
                            } catch (Exception e) {
                                e.printStackTrace(); // TODO: double check this exception
                                throw new RuntimeException(
                                        "Reader failed for chunk " + task.startByte + "-" + task.endByte, e);
                            } finally {
                                readerLatch.countDown();
                            }
                        });
                    }

                    // Wait for all readers to finish
                    readerLatch.await();
                    ioPool.shutdown();
                    if (!ioPool.awaitTermination(1, TimeUnit.MINUTES)) {
                        throw new RuntimeException("IO pool did not terminate within timeout");
                    }

                    // Send one poison pill per processor
                    for (int i = 0; i < processThreads; i++) {
                        dataQueue.put(List.of(ItemData.poisonPill()));
                    }

                    // Wait for processors to finish
                    processorsLatch.await();
                    processPool.shutdown();
                    if (!processPool.awaitTermination(1, TimeUnit.MINUTES)) {
                        throw new RuntimeException("Process pool did not terminate within timeout");
                    }

                    validators
                            .values()
                            .forEach(validatorSet -> validatorSet.forEach(validator -> {
                                try {
                                    validator.validate();
                                    validationListeners.forEach(
                                            listener -> listener.onValidationCompleted(validator.getTag()));
                                } catch (ValidationException e) {
                                    log.error("Validation failed: {}", e.getMessage());
                                }
                            }));

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

                    log.debug("Total boundary search time: {} ms", totalBoundarySearchMillis.get());
                    log.debug("Total processing time: {} ms", System.currentTimeMillis() - startTime);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Map<Type, Set<Validator>> createAndInitValidators(
            @NonNull final MerkleNodeState state,
            @NonNull final String[] tags,
            @NonNull final List<ValidationListener> validationListeners) {
        final Set<String> tagSet = Set.of(tags);

        final Map<Type, Set<Validator>> validatorsMap = new HashMap<>();

        // 1. Populate map with validators that match supplied tags
        final Set<Validator> hashRecordValidators = new CopyOnWriteArraySet<>();
        final Validator hashRecordValidator = new HashRecordIntegrityValidator();
        if (tagSet.contains(hashRecordValidator.getTag())) {
            hashRecordValidators.add(hashRecordValidator);
        }
        if (!hashRecordValidators.isEmpty()) {
            validatorsMap.put(Type.P2H, hashRecordValidators);
        }
        // hdhm
        final Set<Validator> hdhmBucketValidators = new CopyOnWriteArraySet<>();
        final Validator hdhmBucketValidator = new HdhmBucketIntegrityValidator();
        if (tagSet.contains(hdhmBucketValidator.getTag())) {
            hdhmBucketValidators.add(hdhmBucketValidator);
        }
        if (!hdhmBucketValidators.isEmpty()) {
            validatorsMap.put(Type.K2P, hdhmBucketValidators);
        }
        // leaf, etc.
        final Set<Validator> leafBytesValidators = new CopyOnWriteArraySet<>();
        final Validator leafBytesValidator = new LeafBytesIntegrityValidator();
        if (tagSet.contains(leafBytesValidator.getTag())) {
            leafBytesValidators.add(leafBytesValidator);
        }
        final Validator accountValidator = new AccountAndSupplyValidator();
        if (tagSet.contains(accountValidator.getTag())) {
            leafBytesValidators.add(accountValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final Validator tokenRelationsValidator = new TokenRelationsIntegrityValidator();
        if (tagSet.contains(tokenRelationsValidator.getTag())) {
            leafBytesValidators.add(tokenRelationsValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final Validator entityIdCountValidator = new EntityIdCountValidator();
        if (tagSet.contains(entityIdCountValidator.getTag())) {
            leafBytesValidators.add(entityIdCountValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final Validator entityIdUniquenessValidator = new EntityIdUniquenessValidator();
        if (tagSet.contains(entityIdUniquenessValidator.getTag())) {
            leafBytesValidators.add(entityIdUniquenessValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }

        // 2. Initialize validators and remove if initialization fails
        // Use an iterator on the map values to allow safe removal of empty sets
        final java.util.Iterator<Set<Validator>> mapIterator =
                validatorsMap.values().iterator();
        while (mapIterator.hasNext()) {
            final Set<Validator> validatorSet = mapIterator.next();
            final java.util.Iterator<Validator> validatorIterator = validatorSet.iterator();

            while (validatorIterator.hasNext()) {
                final Validator validator = validatorIterator.next();
                validationListeners.forEach(listener -> listener.onValidationStarted(validator.getTag()));
                try {
                    validator.initialize(state);
                } catch (ValidationException e) {
                    validationListeners.forEach(listener -> listener.onValidationFailed(e));
                    // 3. Remove validator entry if initialization failed
                    validatorIterator.remove();
                }
            }

            // Clean up: remove the entry from the map if no validators remain for this type
            if (validatorSet.isEmpty()) {
                mapIterator.remove();
            }
        }

        // 4. Return the fully initialized and cleaned map
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
