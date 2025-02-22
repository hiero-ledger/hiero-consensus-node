// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.state;

/**
 * An object that can be started.
 */
@FunctionalInterface
public interface Startable {

    /**
     * Start this object.
     */
    void start();
}
