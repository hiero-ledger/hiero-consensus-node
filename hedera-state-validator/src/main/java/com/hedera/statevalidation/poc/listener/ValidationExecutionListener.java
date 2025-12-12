// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.listener;

import com.hedera.statevalidation.poc.util.ValidationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A {@link ValidationListener} implementation that logs validation lifecycle events
 * and tracks overall validation failure status.
 */
public class ValidationExecutionListener implements ValidationListener {

    private static final Logger log = LogManager.getLogger(ValidationExecutionListener.class);

    private final List<ValidationException> failedValidations = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean failed = false;

    /**
     * {@inheritDoc}
     * <p>Logs the validator start event at INFO level.
     */
    @Override
    public void onValidationStarted(@NonNull final String tag) {
        log.info("Validator [{}] started", tag);
    }

    /**
     * {@inheritDoc}
     * <p>Logs the validator completion event at INFO level.
     */
    @Override
    public void onValidationCompleted(@NonNull final String tag) {
        log.info("Validator [{}] completed successfully", tag);
    }

    /**
     * {@inheritDoc}
     * <p>Sets the failed flag and logs the failure event at ERROR level.
     */
    @Override
    public void onValidationFailed(@NonNull final ValidationException error) {
        this.failed = true;
        this.failedValidations.add(error);
        log.error("Validator [{}] failed: {}", error.getValidatorTag(), error.getMessage(), error);
    }

    /**
     * Returns whether any validator has failed.
     *
     * @return {@code true} if at least one validator failed, {@code false} otherwise
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Returns the list of validation exceptions for all failed validations.
     *
     * @return unmodifiable list of validation failures
     */
    public List<ValidationException> getFailedValidations() {
        return List.copyOf(failedValidations);
    }
}
