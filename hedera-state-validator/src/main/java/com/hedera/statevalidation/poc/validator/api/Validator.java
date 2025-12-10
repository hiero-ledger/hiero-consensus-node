// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator.api;

import com.swirlds.platform.state.snapshot.DeserializedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Base interface for all validators with a clear lifecycle used in the parallel state validation pipeline.
 *
 * <h2>Validator Lifecycle</h2>
 * <p>Each validator follows a three-phase lifecycle:
 * <ol>
 *     <li><b>Initialization</b> - {@link #initialize(DeserializedSignedState)} is called once before any data
 *         processing begins. Validators should extract required state references and initialize counters.</li>
 *     <li><b>Processing</b> - Data items are streamed to validators (for pipeline validators).</li>
 *     <li><b>Validation</b> - {@link #validate()} is called once after all data processing is complete
 *         to perform final assertions and report results.</li>
 * </ol>
 *
 * <h2>Thread Safety Contract</h2>
 * <p>Validator implementations are invoked concurrently from multiple processor threads.
 * They are safe to use because:
 * <ul>
 *     <li>The state being validated is read-only (no concurrent writes)</li>
 *     <li>All counters/accumulators must use atomic types (e.g., {@code AtomicLong}, {@code AtomicInteger})</li>
 *     <li>The underlying MerkleDB infrastructure supports concurrent reads</li>
 *     <li>Validators are stored in {@code CopyOnWriteArraySet} allowing safe removal on failure</li>
 * </ul>
 *
 * <h2>Error Handling</h2>
 * <p>Validators should throw {@link com.hedera.statevalidation.poc.util.ValidationException} when
 * validation fails. When an exception is thrown:
 * <ul>
 *     <li>The validator is automatically removed from the active validator set</li>
 *     <li>Registered {@link com.hedera.statevalidation.poc.listener.ValidationListener listeners}
 *         are notified of the failure</li>
 *     <li>Processing continues for remaining validators</li>
 * </ul>
 *
 * @see com.hedera.statevalidation.poc.util.ValidationException
 * @see com.hedera.statevalidation.poc.listener.ValidationListener
 */
public interface Validator {

    /**
     * Special tag that matches all validators. When specified, all available validators will be run.
     */
    String ALL_TAG = "all";

    /**
     * Returns the unique identifier tag for this validator.
     *
     * <p>The tag is used for:
     * <ul>
     *     <li>Filtering which validators to run via command-line parameters</li>
     *     <li>Logging and error reporting to identify which validator produced output</li>
     *     <li>Listener notifications about validation lifecycle events</li>
     * </ul>
     *
     * @return a non-null, unique string identifier for this validator
     */
    @NonNull
    String getTag();

    /**
     * Initializes the validator with access to the deserialized signed state.
     *
     * <p>This method is called once before any data processing begins. Implementations should:
     * <ul>
     *     <li>Extract and store references to required state components (e.g., readable states,
     *         virtual maps, entity stores) via {@code deserializedSignedState.reservedSignedState().get().getState()}</li>
     *     <li>Initialize any atomic counters or thread-safe collections needed for tracking</li>
     *     <li>Perform any pre-validation setup or initial state queries</li>
     *     <li>Access the original hash via {@code deserializedSignedState.originalHash()} if needed</li>
     * </ul>
     *
     * <p>If initialization fails, the validator should throw a
     * {@link com.hedera.statevalidation.poc.util.ValidationException} and will be excluded
     * from further processing.
     *
     * @param deserializedSignedState the deserialized signed state providing read-only access to all service states,
     *              virtual maps, data sources, and the original hash; must not be null
     * @throws com.hedera.statevalidation.poc.util.ValidationException if initialization fails
     *         and the validator cannot proceed
     */
    void initialize(@NonNull DeserializedSignedState deserializedSignedState);

    /**
     * Finalizes validation and asserts results.
     *
     * <p>This method is called once after all data processing is complete. Implementations should:
     * <ul>
     *     <li>Perform final assertions using
     *         {@link com.hedera.statevalidation.poc.util.ValidationAssertions}</li>
     *     <li>Log summary statistics or results</li>
     *     <li>Compare accumulated counts against expected values from state metadata</li>
     * </ul>
     *
     * <p>If any validation assertion fails, throw a
     * {@link com.hedera.statevalidation.poc.util.ValidationException} with details about the failure.
     *
     * @throws com.hedera.statevalidation.poc.util.ValidationException if any validation
     *         assertion fails
     */
    void validate();
}
