// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator.listener;

import com.hedera.statevalidation.validator.util.ValidationException;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Listener interface for receiving notifications about validation lifecycle events.
 *
 * <p>This interface enables observers to track the progress and outcome of validators
 * during state validation. Listeners are registered with the validation command and
 * receive callbacks at key points in each validator's lifecycle:
 * <ul>
 *     <li>When a validator starts initialization</li>
 *     <li>When a validator successfully completes validation</li>
 *     <li>When a validator fails during initialization or validation</li>
 * </ul>
 *
 * <p><b>Thread Safety:</b> Implementations must be thread-safe as callbacks
 * may be invoked concurrently from multiple processor threads. The validation
 * pipeline processes data items in parallel, and validators may fail at any
 * point during concurrent processing.
 *
 * <p><b>Lifecycle:</b> Listeners are notified in the following order for each validator:
 * <ol>
 *     <li>{@link #onValidationStarted(String)} - Called before validator initialization</li>
 *     <li>Either {@link #onValidationCompleted(String)} on success, or
 *         {@link #onValidationFailed(ValidationException)} on failure</li>
 * </ol>
 *
 * @see ValidationException
 */
public interface ValidationListener {

    /**
     * Called when a validator begins its validation process.
     *
     * <p>This callback is invoked before the validator's initialization phase.
     * It provides an opportunity to log or track which validators are being executed.
     *
     * @param validatorName the unique name of the validator that is starting
     */
    default void onValidationStarted(@NonNull String validatorName) {}

    /**
     * Called when a validator successfully completes its validation.
     *
     * <p>This callback is invoked after the validator's {@code validate()} method
     * returns without throwing an exception, indicating that all validation
     * assertions passed.
     *
     * @param validatorName the unique name of the validator that completed successfully
     */
    default void onValidationCompleted(@NonNull String validatorName) {}

    /**
     * Called when a validator fails during initialization, processing, or final validation.
     *
     * @param error the validation exception containing the validator name and failure details
     */
    default void onValidationFailed(@NonNull ValidationException error) {}
}
