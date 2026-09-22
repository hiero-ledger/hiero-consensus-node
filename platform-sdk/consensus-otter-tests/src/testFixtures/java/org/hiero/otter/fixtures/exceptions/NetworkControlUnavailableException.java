// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.exceptions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Thrown when the test environment's network-control mechanism is transiently unable to apply a change to the network,
 * for example when the container environment's Toxiproxy control server keeps timing out because a toxic goroutine is
 * wedged on a backpressured link.
 *
 * <p>This describes the mechanism that <em>applies</em> network conditions being temporarily unavailable &ndash; not the
 * modeled network being partitioned, which is a condition tests deliberately create. It is a self-healing, retryable
 * condition rather than a bug, so it is a distinct type: callers that modify the network as a best-effort operation
 * &ndash; such as the chaos bot &ndash; can catch <em>this</em> exception specifically and carry on, while genuine errors
 * (a fixture bug, a failed node kill, a wiring mistake) still propagate and fail the test.
 */
public class NetworkControlUnavailableException extends RuntimeException {

    /**
     * Constructs a new {@link NetworkControlUnavailableException} with the given message.
     *
     * @param message the detail message
     */
    public NetworkControlUnavailableException(@NonNull final String message) {
        super(message);
    }

    /**
     * Constructs a new {@link NetworkControlUnavailableException} with the given message and cause.
     *
     * @param message the detail message
     * @param cause the underlying cause, or {@code null} if none
     */
    public NetworkControlUnavailableException(@NonNull final String message, @Nullable final Throwable cause) {
        super(message, cause);
    }
}
