// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.assertions;

/**
 * A {@link ContinuousAssertion} checks assertions continuously until the end of the test or until stopped manually by
 * calling {@link #destroy()}.
 */
@SuppressWarnings("unused")
public interface ContinuousAssertion {

    /**
     * Pauses the continuous assertion. The assertion will not be checked until it is resumed.
     *
     * <p>This method is idempotent, meaning that it is safe to call multiple times.
     */
    void pause();

    /**
     * Resumes the continuous assertion. The assertion will be checked again.
     *
     * <p>This method is idempotent, meaning that it is safe to call multiple times.
     */
    void resume();

    /**
     * Destroys the continuous assertion. All resources are released. Once destroyed, the assertions cannot be restarted again.
     *
     * <p>This method is idempotent, meaning that it is safe to call multiple times.
     */
    void destroy();
}
