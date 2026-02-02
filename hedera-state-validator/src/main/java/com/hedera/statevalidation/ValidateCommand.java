// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.validator.AccountAndSupplyValidator.ACCOUNT_TAG;
import static com.hedera.statevalidation.validator.EntityIdCountValidator.ENTITY_ID_COUNT_TAG;
import static com.hedera.statevalidation.validator.EntityIdUniquenessValidator.ENTITY_ID_UNIQUENESS_TAG;
import static com.hedera.statevalidation.validator.HashRecordIntegrityValidator.INTERNAL_TAG;
import static com.hedera.statevalidation.validator.HdhmBucketIntegrityValidator.HDHM_TAG;
import static com.hedera.statevalidation.validator.LeafBytesIntegrityValidator.LEAF_TAG;
import static com.hedera.statevalidation.validator.RehashValidator.REHASH_TAG;
import static com.hedera.statevalidation.validator.RootHashValidator.ROOT_HASH_TAG;
import static com.hedera.statevalidation.validator.TokenRelationsIntegrityValidator.TOKEN_RELATIONS_TAG;
import static com.hedera.statevalidation.validator.Validator.ALL_TAG;
import static com.hedera.statevalidation.validator.ValidatorRegistry.createAndInitIndividualValidators;
import static com.hedera.statevalidation.validator.ValidatorRegistry.createAndInitValidators;

import com.hedera.statevalidation.report.SlackReportBuilder;
import com.hedera.statevalidation.util.StateUtils;
import com.hedera.statevalidation.validator.Validator;
import com.hedera.statevalidation.validator.listener.ValidationExecutionListener;
import com.hedera.statevalidation.validator.listener.ValidationListener;
import com.hedera.statevalidation.validator.model.DiskDataItem.Type;
import com.hedera.statevalidation.validator.pipeline.ValidationPipelineExecutor;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.merkledb.MerkleDbDataSource;
import com.swirlds.state.MerkleNodeState;
import com.swirlds.virtualmap.VirtualMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

@SuppressWarnings("FieldMayBeFinal")
@Command(name = "validate", mixinStandardHelpOptions = true, description = "Validates the state of the node.")
public class ValidateCommand implements Callable<Integer> {

    private static final Logger log = LogManager.getLogger(ValidateCommand.class);

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
                    + ", "
                    + REHASH_TAG
                    + ", "
                    + ROOT_HASH_TAG
                    + "]")
    private String[] tags = {
        ALL_TAG,
        INTERNAL_TAG,
        LEAF_TAG,
        HDHM_TAG,
        ACCOUNT_TAG,
        TOKEN_RELATIONS_TAG,
        ENTITY_ID_COUNT_TAG,
        ENTITY_ID_UNIQUENESS_TAG,
        REHASH_TAG,
        ROOT_HASH_TAG
    };

    private ValidateCommand() {}

    @Override
    public Integer call() {
        try {
            // Initialize state
            parent.initializeStateDir();
            final MerkleNodeState state = StateUtils.getState();
            final VirtualMap virtualMap = (VirtualMap) state.getRoot();
            final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();
            if (vds.getFirstLeafPath() == -1) {
                log.info("Skipping the validation as there is no data");
                return 0;
            }

            // Initialize validators and listeners
            final var validationExecutionListener = new ValidationExecutionListener();
            final List<ValidationListener> validationListeners = List.of(validationExecutionListener);

            final long startTime = System.currentTimeMillis();

            // Run individual validators (those that don't use the pipeline)
            final List<Validator> individualValidators =
                    createAndInitIndividualValidators(state, tags, validationListeners);
            for (final Validator validator : individualValidators) {
                try {
                    validator.validate();
                    validationListeners.forEach(listener -> listener.onValidationCompleted(validator.getTag()));
                } catch (final ValidationException e) {
                    validationListeners.forEach(listener -> listener.onValidationFailed(e));
                } catch (final Exception e) {
                    validationListeners.forEach(listener -> listener.onValidationFailed(
                            new ValidationException(validator.getTag(), "Unexpected exception: " + e.getMessage(), e)));
                }
            }

            // Run pipeline
            final Map<Type, Set<Validator>> validators = createAndInitValidators(state, tags, validationListeners);
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

            log.info("Time spent for validation: {} ms", System.currentTimeMillis() - startTime);

            // Return result
            if (!pipelineSuccess || validationExecutionListener.isFailed()) {
                // Generate Slack report for failures
                final List<SlackReportBuilder.ValidationFailure> failures =
                        validationExecutionListener.getFailedValidations().stream()
                                .map(e -> new SlackReportBuilder.ValidationFailure(e.getValidatorTag(), e.getMessage()))
                                .toList();
                SlackReportBuilder.generateReport(failures);

                return 1;
            }

            return 0;

        } catch (final RuntimeException e) {
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Validation interrupted", e);
        } catch (final Exception e) {
            throw new IllegalStateException("Validation failed unexpectedly", e);
        }
    }
}
