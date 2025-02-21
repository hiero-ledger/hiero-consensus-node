// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.state;

/**
 * Phases in an object's {@link Lifecycle}.
 */
public enum LifecyclePhase {
    /**
     * The object's constructor has been called, but {@link Lifecycle#start()} has not yet been called.
     */
    NOT_STARTED,
    /**
     * {@link Lifecycle#start()} has been called and has not yet finished. This is an optional phase, some objects
     * may jump straight to {@link #STARTED}.
     */
    STARTING,
    /**
     * {@link Lifecycle#start()} has been called and has been completed.
     */
    STARTED,
    /**
     * {@link Lifecycle#stop()} has been called but has not yet finished. This is an optional phase, some objects
     * may jump straight to {@link #STOPPED}.
     */
    STOPPING,
    /**
     * {@link Lifecycle#stop()} has been called and has completed.
     */
    STOPPED,
    /**
     * This object has encountered an unrecoverable error and is broken.
     */
    ERROR
}
