// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.state;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An exception type thrown when something is in the wrong {@link LifecyclePhase}.
 */
public class LifecycleException extends IllegalStateException {

    public LifecycleException(@NonNull final String message) {
        super(message);
    }

    public LifecycleException(@NonNull final String message, @NonNull final Throwable cause) {
        super(message, cause);
    }
}
