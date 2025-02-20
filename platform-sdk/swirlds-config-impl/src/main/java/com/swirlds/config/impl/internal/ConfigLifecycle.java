// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.internal;

/**
 * Basic lifecycle definition for the config. This will be replaced with a general interface once we have a platform
 * context API.
 */
public interface ConfigLifecycle {

    /**
     * Inits the component. Calling this on a component that is already initialized might result in an exception.
     */
    void init();

    /**
     * Disposes the component. This can be called on any state and must be able to be called on an already disposed
     * component.
     */
    void dispose();

    /**
     * Returns true if the component is initialized ({@link #init()} has been called).
     *
     * @return true if the component is initialized
     */
    boolean isInitialized();

    /**
     * Throws a {@link IllegalStateException} if the component is initialized.
     */
    default void throwIfInitialized() {
        if (isInitialized()) {
            throw new IllegalStateException(getClass().getName() + " is initialized");
        }
    }

    /**
     * Throws a {@link IllegalStateException} if the component is not initialized.
     */
    default void throwIfNotInitialized() {
        if (!isInitialized()) {
            throw new IllegalStateException(getClass().getName() + " is not initialized");
        }
    }
}
