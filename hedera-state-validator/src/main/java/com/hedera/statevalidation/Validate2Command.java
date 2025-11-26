// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.poc.validator.AccountAndSupplyValidator.ACCOUNT_TAG;
import static com.hedera.statevalidation.poc.validator.EntityIdCountValidator.ENTITY_ID_COUNT_TAG;
import static com.hedera.statevalidation.poc.validator.EntityIdUniquenessValidator.ENTITY_ID_UNIQUENESS_TAG;
import static com.hedera.statevalidation.poc.validator.HashRecordIntegrityValidator.INTERNAL_TAG;
import static com.hedera.statevalidation.poc.validator.HdhmBucketIntegrityValidator.HDHM_TAG;
import static com.hedera.statevalidation.poc.validator.LeafBytesIntegrityValidator.LEAF_TAG;
import static com.hedera.statevalidation.poc.validator.TokenRelationsIntegrityValidator.TOKEN_RELATIONS_TAG;
import static com.hedera.statevalidation.poc.validator.api.Validator.ALL_TAG;

import com.hedera.statevalidation.poc.listener.ValidationExecutionListener;
import com.hedera.statevalidation.poc.listener.ValidationListener;
import com.hedera.statevalidation.poc.model.DiskDataItem.Type;
import com.hedera.statevalidation.poc.pipeline.ValidationPipelineExecutor;
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
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArraySet;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@SuppressWarnings("FieldMayBeFinal")
@Command(
        name = "validate2",
        mixinStandardHelpOptions = true,
        description = "Validate command v2. Validates the state by running some of the validators in parallel.")
public class Validate2Command implements Callable<Integer> {

    @ParentCommand
    private StateOperatorCommand parent;

    @Option(
            names = {"-io", "--io-threads"},
            description = "Number of IO threads for reading from disk. Default: 4.")
    private int ioThreads = 4;

    @Option(
            names = {"-p", "--process-threads"},
            description = "Number of CPU threads for processing chunks. Default: 6.")
    private int processThreads = 6;

    @Option(
            names = {"-q", "--queue-capacity"},
            description = "Queue capacity for backpressure control. Default: 100.")
    private int queueCapacity = 100;

    @Option(
            names = {"-b", "--batch-size"},
            description = "Batch size for processing items. Default: 10.")
    private int batchSize = 10;

    @Option(
            names = {"-mcs", "--min-chunk-size-mib"},
            description = "Minimum chunk size in mebibytes (MiB) for file reading. Default: 128 MiB.")
    private int minChunkSizeMib = 128;

    @Option(
            names = {"-c", "--chunk-multiplier"},
            description =
                    "Multiplier for IO threads to determine target number of chunks (higher value = more, smaller chunks). Default: 2.")
    private int chunkMultiplier = 2;

    @Option(
            names = {"-bs", "--buffer-size-kib"},
            description = "Buffer size in kibibytes (KiB) for file reading operations. Default: 128 KiB.")
    private int bufferSizeKib = 128;

    @CommandLine.Parameters(
            arity = "1..*",
            description = "Tag to run: ["
                    + ALL_TAG
                    + ", "
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
        ALL_TAG,
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
    public Integer call() {
        try {
            // Initialize state
            parent.initializeStateDir();
            final var deserializedSignedState = StateUtils.getDeserializedSignedState();
            //noinspection resource -- doesn't matter in this context
            final MerkleNodeState state =
                    deserializedSignedState.reservedSignedState().get().getState();
            final VirtualMap virtualMap = (VirtualMap) state.getRoot();
            final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();

            // Initialize validators and listeners
            final var validationExecutionListener = new ValidationExecutionListener();
            final List<ValidationListener> validationListeners = List.of(validationExecutionListener);
            final Map<Type, CopyOnWriteArraySet<Validator>> validators =
                    createAndInitValidators(state, tags, validationListeners);

            // Run pipeline
            final boolean pipelineSuccess = ValidationPipelineExecutor.run(
                    vds,
                    validators,
                    validationListeners,
                    ioThreads,
                    processThreads,
                    queueCapacity,
                    batchSize,
                    minChunkSizeMib,
                    chunkMultiplier,
                    bufferSizeKib);

            // Return result
            if (!pipelineSuccess) {
                return 1;
            }
            return validationExecutionListener.isFailed() ? 1 : 0;

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
        final boolean runAll = tagSet.contains(ALL_TAG);

        final Map<Type, CopyOnWriteArraySet<Validator>> validatorsMap = new HashMap<>();

        // 1. Populate map with validators that match supplied tags
        final var hashRecordValidators = new CopyOnWriteArraySet<Validator>();
        final var hashRecordValidator = new HashRecordIntegrityValidator();
        if (runAll || tagSet.contains(hashRecordValidator.getTag())) {
            hashRecordValidators.add(hashRecordValidator);
        }
        if (!hashRecordValidators.isEmpty()) {
            validatorsMap.put(Type.P2H, hashRecordValidators);
        }
        // hdhm
        final var hdhmBucketValidators = new CopyOnWriteArraySet<Validator>();
        final var hdhmBucketValidator = new HdhmBucketIntegrityValidator();
        if (runAll || tagSet.contains(hdhmBucketValidator.getTag())) {
            hdhmBucketValidators.add(hdhmBucketValidator);
        }
        if (!hdhmBucketValidators.isEmpty()) {
            validatorsMap.put(Type.K2P, hdhmBucketValidators);
        }
        // leaf, etc.
        final var leafBytesValidators = new CopyOnWriteArraySet<Validator>();
        final var leafBytesValidator = new LeafBytesIntegrityValidator();
        if (runAll || tagSet.contains(leafBytesValidator.getTag())) {
            leafBytesValidators.add(leafBytesValidator);
        }
        final var accountValidator = new AccountAndSupplyValidator();
        if (runAll || tagSet.contains(accountValidator.getTag())) {
            leafBytesValidators.add(accountValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final var tokenRelationsValidator = new TokenRelationsIntegrityValidator();
        if (runAll || tagSet.contains(tokenRelationsValidator.getTag())) {
            leafBytesValidators.add(tokenRelationsValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final var entityIdCountValidator = new EntityIdCountValidator();
        if (runAll || tagSet.contains(entityIdCountValidator.getTag())) {
            leafBytesValidators.add(entityIdCountValidator);
        }
        if (!leafBytesValidators.isEmpty()) {
            validatorsMap.put(Type.P2KV, leafBytesValidators);
        }
        final var entityIdUniquenessValidator = new EntityIdUniquenessValidator();
        if (runAll || tagSet.contains(entityIdUniquenessValidator.getTag())) {
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
                } catch (final ValidationException e) {
                    validationListeners.forEach(listener -> listener.onValidationFailed(e));
                    return true; // remove validator
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
}
