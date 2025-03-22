// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import com.swirlds.common.Releasable;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;
import org.hiero.consensus.model.constructable.RuntimeConstructable;

/**
 * Keeps track of a {@link RuntimeConstructable} object. When the object is released,
 * this record will also be released.
 */
public class RuntimeObjectRecord implements Releasable {

    /**
     * Has this object been released?
     */
    private boolean released = false;

    /**
     * An action to run when this record becomes released.
     */
    private final Consumer<RuntimeObjectRecord> cleanupAction;

    /**
     * The time when this object was created.
     */
    private final Instant creationTime;

    private final Object metadata;

    /**
     * Create a new record of an object being tracked.
     *
     * @param creationTime
     * 		the creation time of the object
     * @param cleanupAction
     * 		an operation that will be performed when {@link #release()} is called
     */
    public RuntimeObjectRecord(final Instant creationTime, final Consumer<RuntimeObjectRecord> cleanupAction) {
        this(creationTime, cleanupAction, null);
    }

    /**
     * Create a new record of an object being tracked.
     *
     * @param creationTime
     * 		the creation time of the object
     * @param cleanupAction
     * 		an operation that will be performed when {@link #release()} is called
     * @param metadata
     * 		optional arbitrary metadata for debugging purposes, can be null
     */
    public RuntimeObjectRecord(
            final Instant creationTime, final Consumer<RuntimeObjectRecord> cleanupAction, final Object metadata) {

        this.creationTime = creationTime;
        this.cleanupAction = cleanupAction;
        this.metadata = metadata;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean release() {
        throwIfDestroyed();
        released = true;
        if (cleanupAction != null) {
            cleanupAction.accept(this);
        }
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean isDestroyed() {
        return released;
    }

    /**
     * Get the time when this object was created.
     */
    public Instant getCreationTime() {
        return creationTime;
    }

    /**
     * Get the age of this record.
     *
     * @param now
     * 		the current time
     * @return the time between the creation of this record and now
     */
    public Duration getAge(final Instant now) {
        return Duration.between(creationTime, now);
    }

    /**
     * Get the metadata associated with this record.
     *
     * @param <T>
     * 		the type of the metadata
     * @return the associated metadata
     * @throws ClassCastException
     * 		if the metadata is not of the expected type
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata() {
        return (T) metadata;
    }
}
