// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import edu.umd.cs.findbugs.annotations.Nullable;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Enumeration of common failures encountered in block streaming.
 */
public enum FailureType {
    /**
     * Failure that represents a {@link java.net.ConnectException} with a message of "Connection refused".
     */
    CONNECTION_REFUSED,
    /**
     * Failure that represents a {@link java.net.SocketException} with a message of "Socket closed".
     */
    SOCKET_CLOSED,
    /**
     * Failure that represents a {@link java.net.SocketException} with a message of "Broken pipe".
     */
    BROKEN_PIPE,
    /**
     * Failure that represents a {@link java.net.UnknownHostException} or a {@link java.lang.IllegalArgumentException}
     * with a message of "Failed to get address for host".
     */
    UNKNOWN_HOST,
    /**
     * Failure that represents a {@link java.lang.InterruptedException}.
     */
    INTERRUPTED,
    /**
     * Failure that represents a {@link java.util.concurrent.TimeoutException}.
     */
    TIMEOUT,
    /**
     * Failure is not a known common error.
     */
    OTHER;

    /**
     * @return true if the failure is a common error, else false
     */
    public boolean isCommonFailure() {
        return OTHER != this;
    }

    /**
     * For the provided {@linkplain Throwable}, determine if it is a common failure type. This will check the top-level
     * exception and then any chained causes.
     *
     * @param failure the failure to check
     * @return the type of common failure, if the provided error is one, else {@link FailureType#OTHER} is returned
     */
    public static FailureType findFailureType(@Nullable final Throwable failure) {
        if (failure == null) {
            return OTHER;
        }

        Throwable t = failure;

        while (t != null) {
            final String msg = t.getMessage() == null ? null : t.getMessage().toLowerCase();
            // spotless:off
            final FailureType type = switch (t) {
                case ConnectException _ when msg != null && msg.contains("connection refused") -> CONNECTION_REFUSED;
                case SocketException _ when msg != null && msg.contains("socket closed") -> SOCKET_CLOSED;
                case SocketException _ when msg != null && msg.contains("broken pipe") -> BROKEN_PIPE;
                case UnknownHostException _ -> UNKNOWN_HOST;
                case IllegalArgumentException _ when msg != null && msg.contains("failed to get address for host") -> UNKNOWN_HOST;
                case InterruptedException _ -> INTERRUPTED;
                case TimeoutException _ -> TIMEOUT;
                default -> null;
            };
            // spotless:on
            if (type != null) {
                return type;
            }

            t = t.getCause();
        }

        return OTHER;
    }
}
