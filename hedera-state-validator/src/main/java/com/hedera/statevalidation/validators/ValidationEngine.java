// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validators;

import static com.hedera.statevalidation.validators.ParallelProcessingUtil.processRange;
import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hedera.statevalidation.listener.ValidationListener;
import com.hedera.statevalidation.merkledb.reflect.MemoryIndexDiskKeyValueStoreW;
import com.hedera.statevalidation.parameterresolver.StateResolver;
import com.hedera.statevalidation.validators.merkledb.ValidateLeafIndex;
import com.hedera.statevalidation.validators.merkledb.ValidateLeafIndexHalfDiskHashMap;
import com.hedera.statevalidation.validators.servicesstate.AccountValidator;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.merkledb.collections.LongList;
import com.swirlds.merkledb.files.DataFileCollection;
import com.swirlds.platform.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import com.swirlds.virtualmap.datasource.VirtualLeafBytes;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ForkJoinTask;
import java.util.function.LongConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.constructable.ConstructableRegistryException;

/**
 * Orchestrates validation execution with optimized traversals.
 * Groups validators to minimize state traversals while maintaining simplicity.
 */
public class ValidationEngine {

    private static final Logger log = LogManager.getLogger(ValidationEngine.class);

    private final List<ValidationListener> listeners = new ArrayList<>();

    // Can be a validation context in the future
    private final MerkleNodeState merkleNodeState;

    public ValidationEngine() throws ConstructableRegistryException, IOException {
        this.merkleNodeState =
                StateResolver.initState().reservedSignedState().get().getState();
    }

    public void addListener(ValidationListener listener) {
        listeners.add(listener);
    }

    public void execute(String[] requestedTags) {
        // Exit if there is nothing to validate
        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        requireNonNull(virtualMap); // intentionally, as this is the engine context -- not validator
        final MerkleDbDataSource virtualDataSource = (MerkleDbDataSource) virtualMap.getDataSource();
        if (virtualDataSource.getFirstLeafPath() == -1) {
            log.info("Skipping the validation for {} as the map is empty", virtualMap.getLabel());
            return;
        }

        Set<String> tagSet = Set.of(requestedTags);

        // Execute independent validators first (no traversal optimization needed)
        executeIndependentValidators(tagSet);

        // Execute traversal-based validators with optimization
        executeTraversalValidators(tagSet);
    }

    private void executeIndependentValidators(Set<String> tags) {
        log.info("Executing independent validators...");

        // Only one for PoC simplicity...
        // Can be some discovery mechanism
        List<StateValidator> independentValidators = Arrays.asList(new ValidateLeafIndexHalfDiskHashMap());

        for (StateValidator validator : independentValidators) {
            if (tags.contains(validator.getTag())) {
                notifyValidationStarted(validator.getTag());
                log.info("Running validator: {}", validator.getTag());
                try {
                    validator.initialize(merkleNodeState);
                    validator.processState(merkleNodeState);
                    validator.validate();
                    notifyValidationCompleted(validator.getTag());
                } catch (ValidationException e) {
                    notifyValidationFailed(e);
                }
            }
        }
    }

    // Current listener will not make sense here, especially SummaryGeneratingListener, which calculates time,
    // So I would like to discuss if we should update them or remove them.
    private void executeTraversalValidators(Set<String> tags) {
        log.info("Executing traversal validators with optimization...");

        // Only two for PoC simplicity...
        // There would be more validators, which will share path range traversal
        // Can be some discovery mechanism
        final List<IndexValidator> indexValidators = Arrays.asList(new ValidateLeafIndex());
        final List<KeyValueValidator> kvValidators = Arrays.asList(new AccountValidator());
        final List<Validator> validators = new ArrayList<>() {
            {
                addAll(indexValidators);
                addAll(kvValidators);
            }
        };

        // initialize all validators
        for (Validator validator : validators) {
            if (tags.contains(validator.getTag())) {
                notifyValidationStarted(validator.getTag());
                log.info("Initializing validator: {}", validator.getTag());
                try {
                    validator.initialize(merkleNodeState);
                } catch (ValidationException e) {
                    // remove tag from the list, so failed validation won't be validated again
                    tags.remove(validator.getTag());
                    notifyValidationFailed(e);
                }
            }
        }

        final VirtualMap virtualMap = (VirtualMap) merkleNodeState.getRoot();
        requireNonNull(virtualMap); // intentionally, as this is the engine context -- not validator
        final MerkleDbDataSource virtualDataSource = (MerkleDbDataSource) virtualMap.getDataSource();
        final var leafStore = new MemoryIndexDiskKeyValueStoreW<>(virtualDataSource.getPathToKeyValue());
        final DataFileCollection leafDfc = leafStore.getFileCollection();
        final LongList leafNodeIndex = virtualDataSource.getPathToDiskLocationLeafNodes();

        // is this debug line needed? (took from ValidateLeafIndex)
        // log.debug(virtualDataSource.getHashStoreDisk().getFilesSizeStatistics());

        final long firstLeafPath = virtualDataSource.getFirstLeafPath();
        final long lastLeafPath = virtualDataSource.getLastLeafPath();

        LongConsumer indexProcessor = path -> {
            // 1. delegate to index based validators
            for (IndexValidator validator : indexValidators) {
                if (tags.contains(validator.getTag())) {
                    try {
                        validator.processIndex(path);
                    } catch (ValidationException e) {
                        // remove tag from the list, so failed validation won't be validated again
                        tags.remove(validator.getTag());
                        notifyValidationFailed(e);
                    }
                }
            }

            // 2. delegate to k/v based validators
            try {
                final long dataLocation = leafNodeIndex.get(path, -1);
                var data = leafDfc.readDataItem(dataLocation);
                if (data != null) {

                    final VirtualLeafBytes<?> leafRecord = VirtualLeafBytes.parseFrom(data);
                    final Bytes keyBytes = leafRecord.keyBytes();
                    final Bytes valueBytes = leafRecord.valueBytes();

                    for (KeyValueValidator validator : kvValidators) {
                        if (tags.contains(validator.getTag())) {
                            try {
                                validator.processKeyValue(keyBytes, valueBytes);
                            } catch (ValidationException e) {
                                // remove tag from the list, so failed validation won't be validated again
                                tags.remove(validator.getTag());
                                notifyValidationFailed(e);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                // ignore, it should be caught by index based validators above
            }
        };

        // (simplified for poc) going from firstLeafPath to lastLeafPath
        ForkJoinTask<?> indexTask = processRange(firstLeafPath, lastLeafPath, indexProcessor);
        indexTask.join();

        // run validate method on all validators
        for (Validator validator : validators) {
            if (tags.contains(validator.getTag())) {
                log.info("Validating: {}", validator.getTag());
                try {
                    validator.validate();
                    notifyValidationCompleted(validator.getTag());
                } catch (ValidationException e) {
                    notifyValidationFailed(e);
                }
            }
        }
    }

    // These methods could be passing further some "Validation Context", which can have info
    // about executed validation, for example, and state, which was validated

    private void notifyValidationStarted(String tag) {
        listeners.forEach(listener -> listener.onValidationStarted(tag));
    }

    private void notifyValidationCompleted(String tag) {
        listeners.forEach(listener -> listener.onValidationCompleted(tag));
    }

    private void notifyValidationFailed(ValidationException error) {
        listeners.forEach(listener -> listener.onValidationFailed(error));
    }
}
