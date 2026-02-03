// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.exceptions;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.net.SocketException;

/**
 * This is a collection of static utility methods, such as for comparing and deep cloning of arrays.
 */
public final class ThrowableUtilities {

    private ThrowableUtilities() {}

    /**
     * if it is or caused by SocketException, we should log it with SOCKET_EXCEPTIONS marker
     *
     * @param ex the exception to check
     * @return return true if it is a SocketException or is caused by SocketException; return false otherwise
     */
    public static boolean isOrCausedBySocketException(@Nullable final Throwable ex) {
        return isRootCauseSuppliedType(ex, SocketException.class);
    }

    /**
     * @param e the exception to check
     * @return true if the cause is an IOException
     */
    public static boolean isCausedByIOException(@Nullable final Exception e) {
        return isRootCauseSuppliedType(e, IOException.class);
    }

    /**
     * Unwraps a Throwable and checks the root cause
     *
     * @param t the throwable to unwrap
     * @param type the type to check against
     * @return true if the root cause matches the supplied type
     */
    public static boolean isRootCauseSuppliedType(
            @Nullable final Throwable t, @NonNull final Class<? extends Throwable> type) {
        if (t == null) {
            return false;
        }
        Throwable cause = t;
        // get to the root cause
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return type.isInstance(cause);
    }

    /**
     * Checks all nesting of causes for any instance of the supplied type.
     *
     * @param throwable the throwable to unwrap
     * @param type the type to check against
     * @return true if any of the causes matches the supplied type, false otherwise.
     */
    public static boolean hasAnyCauseSuppliedType(
            @NonNull final Throwable throwable, @NonNull final Class<? extends Throwable> type) {
        Throwable cause = throwable;
        // check all causes
        while (cause != null) {
            if (type.isInstance(cause)) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
