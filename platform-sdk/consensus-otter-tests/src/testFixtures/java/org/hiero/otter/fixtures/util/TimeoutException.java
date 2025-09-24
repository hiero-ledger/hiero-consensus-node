// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.util;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Exception thrown when a timeout occurs while waiting for a condition to be met.
 */
public class TimeoutException extends RuntimeException {

    public TimeoutException(@NonNull final String message) {
        super(message);
    }
}
