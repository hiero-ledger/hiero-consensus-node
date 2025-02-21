// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * A utility for shutting down the JVM.
 */
public final class Shutdown {

    public Shutdown() {}

    /**
     * Shut down the JVM.
     *
     * @param reason   the reason the JVM is being shut down
     * @param exitCode the exit code to return when the JVM has been shut down
     */
    public void shutdown(@Nullable final String reason, @NonNull final SystemExitCode exitCode) {
        Objects.requireNonNull(exitCode);
        SystemExitUtils.exitSystem(exitCode, reason);
    }
}
