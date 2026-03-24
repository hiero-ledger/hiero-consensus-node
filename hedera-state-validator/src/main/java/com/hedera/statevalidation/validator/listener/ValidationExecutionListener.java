// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.listener;

import com.hedera.statevalidation.validator.util.ValidationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link ValidationListener} implementation that logs validation lifecycle events
 * and tracks overall validation failure status.
 */
public class ValidationExecutionListener implements ValidationListener {

    private static final Logger log = LogManager.getLogger(ValidationExecutionListener.class);

    /** Keyed by validator name, value is the validation exception encountered */
    private final Map<String, ValidationException> failedValidations = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     * <p>Logs the validator start event.
     */
    @Override
    public void onValidationStarted(@NonNull final String validatorName) {
        log.info("Validator [{}] started", validatorName);
    }

    /**
     * {@inheritDoc}
     * <p>Logs the validator completion event.
     */
    @Override
    public void onValidationCompleted(@NonNull final String validatorName) {
        log.info("Validator [{}] completed successfully", validatorName);
    }

    /**
     * {@inheritDoc}
     * <p>Tracks failed validations, and logs the failure event.
     */
    @Override
    public void onValidationFailed(@NonNull final ValidationException error) {
        this.failedValidations.putIfAbsent(error.getValidatorName(), error);
        log.error("Validator [{}] failed: {}", error.getValidatorName(), error.getMessage(), error);
    }

    /**
     * Returns whether any validator has failed.
     *
     * @return {@code true} if at least one validator failed, {@code false} otherwise
     */
    public boolean isFailed() {
        return !failedValidations.isEmpty();
    }

    /**
     * Returns the validation exceptions for all failed validators.
     *
     * @return unmodifiable collection of validation failures
     */
    public Collection<ValidationException> getFailedValidations() {
        return List.copyOf(failedValidations.values());
    }
}
