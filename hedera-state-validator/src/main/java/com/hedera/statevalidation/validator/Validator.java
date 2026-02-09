// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.validator;

import com.hedera.statevalidation.validator.listener.ValidationListener;
import com.hedera.statevalidation.validator.util.ValidationAssertions;
import com.hedera.statevalidation.validator.util.ValidationException;
import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Base interface for all validators with a clear lifecycle used in the parallel state validation pipeline.
 *
 * <h2>Validator Lifecycle</h2>
 * <p>Each validator follows a three-phase lifecycle:
 * <ol>
 *     <li><b>Initialization</b> - {@link #initialize(VirtualMapState)} is called once before any data
 *         processing begins. Validators should extract required state references and initialize counters.</li>
 *     <li><b>Processing</b> - Data items are streamed to validators (for pipeline validators).
 *         Independent validators skip this phase entirely.</li>
 *     <li><b>Validation</b> - {@link #validate()} is called once after all data processing is complete
 *         (or immediately after initialization for independent validators) to perform final assertions
 *         and report results.</li>
 * </ol>
 *
 * <h2>Thread Safety Contract</h2>
 * <p>Validator implementations are invoked concurrently from multiple processor threads.
 * They are safe to use because:
 * <ul>
 *     <li>The state being validated is read-only (no concurrent writes)</li>
 *     <li>All counters/accumulators must use atomic types (e.g., {@code AtomicLong}, {@code AtomicInteger})</li>
 *     <li>The underlying MerkleDB infrastructure supports concurrent reads</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <p>Validators should throw {@link ValidationException} when
 * validation fails. When an exception is thrown:
 * <ul>
 *     <li>Registered {@link ValidationListener listeners}
 *         are notified of the failure</li>
 *     <li>Processing continues for remaining validators</li>
 * </ul>
 *
 * @see ValidationException
 * @see ValidationListener
 */
public interface Validator {

    /**
     * Special group that matches all validators. When specified, all available validators will be run.
     */
    String ALL_GROUP = "all";

    /**
     * Returns the group this validator belongs to. There can be multiple validators in the same group.
     * The group is used for filtering which validators to run via command-line parameters.
     *
     * @return a non-null, string identifier for this validator's group
     */
    @NonNull
    String getGroup();

    /**
     * Returns the unique name of this validator.
     *
     * <p>The name is used for:
     * <ul>
     *     <li>Logging and error reporting to identify which validator produced output</li>
     *     <li>Listener notifications about validation lifecycle events</li>
     * </ul>
     *
     * @return a non-null, unique string identifier for this validator
     */
    @NonNull
    String getName();

    /**
     * Initializes the validator with access to the state.
     *
     * <p>This method is called once before any data processing begins. Implementations can:
     * <ul>
     *     <li>Extract and store references to required state components (e.g., readable states,
     *         virtual maps, entity stores)</li>
     *     <li>Initialize any atomic counters or thread-safe collections needed for tracking</li>
     *     <li>Perform any pre-validation setup or initial state queries</li>
     * </ul>
     *
     * <p>If initialization fails, the validator throws an exception and will be excluded
     * from further processing.
     *
     * @param state the state providing read-only access to all service states, virtual maps, data
     *              sources, and the original hash; must not be null
     */
    void initialize(@NonNull final VirtualMapState state);

    /**
     * Finalizes validation and asserts results.
     *
     * <p>This method is called once after all data processing is complete. Implementations should:
     * <ul>
     *     <li>Perform final assertions using
     *         {@link ValidationAssertions}</li>
     *     <li>Log summary statistics or results</li>
     *     <li>Compare accumulated counts against expected values from state metadata</li>
     * </ul>
     *
     * <p>If any validation assertion fails, throw a
     * {@link ValidationException} with details about the failure.
     *
     * @throws ValidationException if any validation
     *         assertion fails
     */
    void validate();
}
