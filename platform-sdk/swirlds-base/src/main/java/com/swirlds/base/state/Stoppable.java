// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.state;

/**
 * An object that can be stopped.
 */
@FunctionalInterface
public interface Stoppable {

    /**
     * Stop this object.
     */
    void stop();
}
