// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.locks;

import com.swirlds.common.threading.locks.internal.AutoLock;
import com.swirlds.common.threading.locks.internal.DefaultIndexLock;
import com.swirlds.common.threading.locks.internal.ResourceLock;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Factory for all custom Locks. Should be used as a facade for the API.
 */
public interface Locks {

    /**
     * Create a new lock for index values.
     *
     * @param parallelism
     * 		the number of unique locks. Higher parallelism reduces chances of collision for non-identical
     * 		* 		indexes at the cost of additional memory overhead.
     * @return a new lock for index values.
     */
    @NonNull
    static IndexLock createIndexLock(final int parallelism) {
        return new DefaultIndexLock(parallelism);
    }

    /**
     * Creates a standard lock that provides the {@link AutoCloseable} semantics. Lock is reentrant.
     *
     * @return the lock
     */
    @NonNull
    static AutoClosableLock createAutoLock() {
        return new AutoLock();
    }

    /**
     * Provides an implementation of {@link AutoClosableLock} which holds a resource that needs to be locked before it
     * can be used
     *
     * @param resource
     * 		the resource
     * @param <T>
     * 		type of the resource
     * @return the lock
     */
    @NonNull
    static <T> AutoClosableResourceLock<T> createResourceLock(@NonNull final T resource) {
        return new ResourceLock<>(new ReentrantLock(), resource);
    }
}
