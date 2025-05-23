// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.result;

/**
 * Common functionality of all results that were collected during an Otter test.
 */
public interface OtterResult {

    /**
     * Clear the result. All results that have been collected previously are discarded.
     */
    void clear();
}
