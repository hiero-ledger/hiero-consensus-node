// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A singleton class that is used to invoke methods on schedulers that do not require any input. Since the current
 * framework does not support such methods, this class is used as a placeholder. This will be removed once the
 * framework is updated to support such methods.
 */
public final class NoInput {
    private static final NoInput INSTANCE = new NoInput();

    private NoInput() {}

    /**
     * @return the singleton instance of this class
     */
    @NonNull
    public static NoInput getInstance() {
        return INSTANCE;
    }
}
