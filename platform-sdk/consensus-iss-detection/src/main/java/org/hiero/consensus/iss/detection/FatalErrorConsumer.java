// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.iss.detection;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.system.SystemExitCode;

/**
 * Invoked when a fatal error has occurred. Consumers should use this method to perform any cleanup or take any final
 * actions before operations are halted.
 */
@FunctionalInterface
public interface FatalErrorConsumer {

    /**
     * A fatal error has occurred. Perform any necessary cleanup or take any final actions before operations are halted.
     *
     * @param msg
     * 		a description of the fatal error, may be {@code null}
     * @param throwable
     * 		the cause of the error, if applicable, otherwise {@code null}
     * @param exitCode
     * 		the exit code to use when shutting down the node, if applicable, otherwise {@code null}
     */
    void fatalError(
            @NonNull final String msg, @Nullable final Throwable throwable, @NonNull final SystemExitCode exitCode);
}
