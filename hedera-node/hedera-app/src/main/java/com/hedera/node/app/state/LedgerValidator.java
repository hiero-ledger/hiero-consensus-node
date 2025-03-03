// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state;

import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Validates a {@link State}, checking to make sure the state is valid. This is used, for example, to verify that
 * no HBAR were lost during an upgrade. Implementations should execute quickly, because validation will delay restarts
 * and/or upgrades. Most validation will happen asynchronously. At the very least, validation should verify that no
 * HBARs were lost or gained.
 */
public interface LedgerValidator {
    /**
     * Performs some kind of validation on the {@link State}.
     *
     * @param state The state to check
     * @throws IllegalStateException If the state is invalid.
     */
    void validate(@NonNull State state) throws IllegalStateException;
}
