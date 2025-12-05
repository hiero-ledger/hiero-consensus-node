// SPDX-License-Identifier: Apache-2.0
package com.hedera.statevalidation.poc.validator.api;

import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Base interface for all validators with a clear lifecycle.
 *
 * <p><b>Thread Safety Contract:</b> Validator implementations are invoked concurrently
 * from multiple processor threads. They are safe to use because:
 * <ul>
 *   <li>The state being validated is read-only (no concurrent writes)</li>
 *   <li>All counters/accumulators must use atomic types</li>
 *   <li>The underlying MerkleDB infrastructure supports concurrent reads</li>
 * </ul>
 */
public interface Validator {

    String getTag();

    void initialize(@NonNull MerkleNodeState state);

    /**
     * Finalize validation and assert results.
     * Called once after all data processing is complete.
     */
    void validate();
}
