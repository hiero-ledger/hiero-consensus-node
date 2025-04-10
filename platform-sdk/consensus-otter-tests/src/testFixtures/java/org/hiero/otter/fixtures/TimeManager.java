// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Interface for managing time in Otter tests.
 *
 * <p>This interface provides methods to wait for a specified duration or other events. Depending on the environment,
 * the implementation may use real time or simulated time.
 */
public interface TimeManager {

    /**
     * Wait for a specified duration.
     *
     * @param waitTime the duration to wait
     */
    void waitFor(@NonNull Duration waitTime) throws InterruptedException;
}
