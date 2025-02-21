// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.state;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This exception is thrown when an operation violates mutability constraints.
 */
public class MutabilityException extends IllegalStateException {

    public MutabilityException(@NonNull final String message) {
        super(message);
    }

    public MutabilityException(@NonNull final String message, @NonNull final Throwable cause) {
        super(message, cause);
    }
}
