// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.utils;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.streams.ContractAction;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A non-atomic wrapper for a {@link ContractAction} value, useful when we want to a "stable reference"
 * to an evolving {@link ContractAction} object in the {@link ActionStack}---even though it will actually
 * need to be recreated each time it changes.
 */
public class ActionWrapper {
    private ContractAction value;

    /**
     * @param value the contract action to be initialized
     */
    public ActionWrapper(@NonNull final ContractAction value) {
        this.value = requireNonNull(value);
    }

    /**
     * @return the contract action
     */
    public @NonNull ContractAction get() {
        return value;
    }

    /**
     * @param value the contract action to be set
     */
    public void set(@NonNull final ContractAction value) {
        this.value = requireNonNull(value);
    }
}
