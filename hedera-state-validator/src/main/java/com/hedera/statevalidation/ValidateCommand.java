// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation;

import static com.hedera.statevalidation.validator.AccountAndSupplyValidator.ACCOUNT_GROUP;
import static com.hedera.statevalidation.validator.EntityIdUniquenessValidator.ENTITY_ID_GROUP;
import static com.hedera.statevalidation.validator.HashRecordIntegrityValidator.INTERNAL_GROUP;
import static com.hedera.statevalidation.validator.HdhmBucketIntegrityValidator.HDHM_GROUP;
import static com.hedera.statevalidation.validator.LeafBytesIntegrityValidator.LEAF_GROUP;
import static com.hedera.statevalidation.validator.RehashValidator.REHASH_GROUP;
import static com.hedera.statevalidation.validator.TokenRelationsIntegrityValidator.TOKEN_RELATIONS_GROUP;
import static com.hedera.statevalidation.validator.Validator.ALL_GROUP;
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
import com.swirlds.state.merkle.VirtualMapState;
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
            description = "Number of CPU threads for processing segments. Default: 6.")
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
            names = {"-mss", "--min-segment-size-mib"},
            description = "Minimum segment size in mebibytes (MiB) for file reading. Default: 128 MiB.")
    private int minSegmentSizeMib = 128;

    @Option(
            names = {"-s", "--segment-multiplier"},
            description =
                    "Multiplier for IO threads to determine target number of segments (higher value = more, smaller segments). Default: 2.")
    private int segmentMultiplier = 2;

    @Option(
            names = {"-bs", "--buffer-size-kib"},
            description = "Buffer size in kibibytes (KiB) for file reading operations. Default: 128 KiB.")
    private int bufferSizeKib = 128;

    @CommandLine.Parameters(
            arity = "1..*",
            description = "Groups to run: ["
                    + ALL_GROUP
                    + ", "
                    + INTERNAL_GROUP
                    + ", "
                    + LEAF_GROUP
                    + ", "
                    + HDHM_GROUP
                    + ", "
                    + ACCOUNT_GROUP
                    + ", "
                    + TOKEN_RELATIONS_GROUP
                    + ", "
                    + ENTITY_ID_GROUP
                    + ", "
                    + REHASH_GROUP
                    + "]")
    private String[] validationGroups = {
        ALL_GROUP,
        INTERNAL_GROUP,
        LEAF_GROUP,
        HDHM_GROUP,
        ACCOUNT_GROUP,
        TOKEN_RELATIONS_GROUP,
        ENTITY_ID_GROUP,
        REHASH_GROUP
    };

    private ValidateCommand() {}

    @Override
    public Integer call() {
        try {
            // Initialize state
            parent.initializeStateDir();
            final VirtualMapState state = StateUtils.getDefaultState();
            final VirtualMap virtualMap = state.getRoot();
            final MerkleDbDataSource vds = (MerkleDbDataSource) virtualMap.getDataSource();
            if (vds.getFirstLeafPath() == -1) {
                log.info("Skipping the validation as there is no data");
                return 0;
            }

            // Initialize validators and listeners
            final var validationExecutionListener = new ValidationExecutionListener();
            final Set<ValidationListener> validationListeners = Set.of(validationExecutionListener);

            final long startTime = System.currentTimeMillis();

            // Run individual validators (those that don't use the pipeline)
            final List<Validator> individualValidators =
                    createAndInitIndividualValidators(state, validationGroups, validationListeners);
            for (final Validator validator : individualValidators) {
                try {
                    validator.validate();
                    validationListeners.forEach(listener -> listener.onValidationCompleted(validator.getName()));
                } catch (final ValidationException e) {
                    validationListeners.forEach(listener -> listener.onValidationFailed(e));
                } catch (final Exception e) {
                    validationListeners.forEach(listener -> listener.onValidationFailed(new ValidationException(
                            validator.getName(), "Unexpected exception: " + e.getMessage(), e)));
                }
            }

            // Run pipeline
            final Map<Type, Set<Validator>> validators =
                    createAndInitValidators(state, validationGroups, validationListeners);
            final boolean pipelineSuccess = ValidationPipelineExecutor.run(
                    vds,
                    validators,
                    validationListeners,
                    ioThreads,
                    processThreads,
                    queueCapacity,
                    batchSize,
                    minSegmentSizeMib,
                    segmentMultiplier,
                    bufferSizeKib);

            log.info("Time spent for validation: {} ms", System.currentTimeMillis() - startTime);

            // Return result
            if (!pipelineSuccess || validationExecutionListener.isFailed()) {
                // Generate Slack report for failures
                final List<SlackReportBuilder.ValidationFailure> failures =
                        validationExecutionListener.getFailedValidations().stream()
                                .map(e ->
                                        new SlackReportBuilder.ValidationFailure(e.getValidatorName(), e.getMessage()))
                                .toList();
                SlackReportBuilder.generateReport(failures);

                // Log final results
                if (!pipelineSuccess) {
                    log.error(
                            "Validation failed. Cause: detected corrupted or invalid entries on disk - see data stats above.");
                }
                if (validationExecutionListener.isFailed()) {
                    log.error("Validation failed. Cause: validation error(s) - see failed validators above.");
                }

                return 1;
            }

            log.info("Validation completed successfully!");

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
