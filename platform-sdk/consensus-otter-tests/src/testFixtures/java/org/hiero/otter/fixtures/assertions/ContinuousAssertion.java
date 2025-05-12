// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

/**
 * A {@link ContinuousAssertion} checks assertions continuously until the end of the test or until stopped manually by
 * calling {@link #stop()}.
 */
public interface ContinuousAssertion {
    /**
     * Stops the continuous assertion. All resources are released. Once stopped, the assertions cannot be restarted again.
     */
    void stop();
}
