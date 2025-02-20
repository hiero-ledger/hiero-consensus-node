// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

/**
 * A {@link WritableStates} implementation that is not buffering changes for a wrapped delegate, but itself knows how to
 * persist changes.
 */
public interface CommittableWritableStates {

    /**
     * Commits all changes.
     */
    void commit();
}
