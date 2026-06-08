// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.io;

import java.io.IOException;

/**
 * Similar to {@link Runnable}, except that the method may throw an {@link java.io.IOException}.
 */
@FunctionalInterface
public interface IORunnable {

    /**
     * Run an operation.
     *
     * @throws IOException
     * 		if an IO problem occurs
     */
    void run() throws IOException;
}
