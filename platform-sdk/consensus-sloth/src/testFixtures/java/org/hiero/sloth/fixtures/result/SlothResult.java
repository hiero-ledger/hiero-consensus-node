// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.result;

/**
 * Common functionality of all results that were collected during a sloth test.
 */
public interface SlothResult {

    /**
     * Clear the result. All results that have been collected previously are discarded.
     */
    void clear();
}
