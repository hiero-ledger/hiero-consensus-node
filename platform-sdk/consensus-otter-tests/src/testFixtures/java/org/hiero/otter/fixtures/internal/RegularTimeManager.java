// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.internal;

import static org.awaitility.Awaitility.await;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import org.awaitility.core.ConditionTimeoutException;
import org.hiero.otter.fixtures.TimeManager;

/**
 * Regular implementation of the {@link TimeManager} interface that uses the system clock.
 */
public class RegularTimeManager implements TimeManager {

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("java:S2925") // Suppressing warning about intended Thread.sleep usage
    public void waitFor(@NonNull final Duration waitTime) throws InterruptedException {
        Thread.sleep(waitTime.toMillis());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean waitForCondition(@NonNull final BooleanSupplier condition, @NonNull final Duration waitTime) {
        try {
            await().atMost(waitTime).until(condition::getAsBoolean);
        } catch (final ConditionTimeoutException ex) {
            return false; // Condition was not met within the specified time
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public @NonNull Time time() {
        return Time.getCurrent();
    }
}
