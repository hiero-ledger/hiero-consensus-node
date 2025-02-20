// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.context;

import com.swirlds.base.context.internal.GlobalContext;
import com.swirlds.base.context.internal.ThreadLocalContext;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A context is a key-value holder that can be used to store information of different parts of the code.
 * <p>
 * The context MUST NOT be used a collection to access data from different parts of the code. Based on that the
 * interface does not provide any methods to access the data. Instead, the context is used to add information to the
 * context that can be used by base systems like logging and metrics to enrich events of the base system "with a
 * context".
 */
public interface Context {

    /**
     * Adds a key-value pair to the context.
     *
     * @param key   the key
     * @param value the value
     * @return an {@link AutoCloseable} that can be used to remove the key-value pair from the context
     * @throws NullPointerException if the key or value is null
     */
    AutoCloseable add(@NonNull String key, @NonNull String value);

    /**
     * Adds a key-value pair to the context.
     *
     * @param key   the key
     * @param value the value
     * @return an {@link AutoCloseable} that can be used to remove the key-value pair from the context
     */
    default AutoCloseable add(@NonNull String key, int value) {
        return add(key, Integer.toString(value));
    }

    /**
     * Adds a key-value pair to the context.
     *
     * @param key   the key
     * @param value the value
     * @return an {@link AutoCloseable} that can be used to remove the key-value pair from the context
     */
    default AutoCloseable add(@NonNull String key, long value) {
        return add(key, Long.toString(value));
    }

    /**
     * Adds a key-value pair to the context.
     *
     * @param key   the key
     * @param value the value
     * @return an {@link AutoCloseable} that can be used to remove the key-value pair from the context
     */
    default AutoCloseable add(@NonNull String key, float value) {
        return add(key, Float.toString(value));
    }

    /**
     * Adds a key-value pair to the context.
     *
     * @param key   the key
     * @param value the value
     * @return an {@link AutoCloseable} that can be used to remove the key-value pair from the context
     */
    default AutoCloseable add(@NonNull String key, double value) {
        return add(key, Double.toString(value));
    }

    /**
     * Adds a key-value pair to the context.
     *
     * @param key   the key
     * @param value the value
     * @return an {@link AutoCloseable} that can be used to remove the key-value pair from the context
     */
    default AutoCloseable add(@NonNull String key, boolean value) {
        return add(key, Boolean.toString(value));
    }

    /**
     * remove a key-value pair from the context if available.
     *
     * @param key the key to remove
     */
    void remove(@NonNull String key);

    /**
     * Returns the global context. The content of the global context is shared by all threads. The global context can be
     * used to store global values like the node id or the ip address of the node.
     *
     * @return the global context
     */
    @NonNull
    static Context getGlobalContext() {
        return GlobalContext.getInstance();
    }

    /**
     * Returns the thread local context. The content of the thread local context is only visible to the current thread.
     * The thread local context can be used to store thread local values like the thread id or the thread name.
     *
     * @return the thread local context
     */
    @NonNull
    static Context getThreadLocalContext() {
        return ThreadLocalContext.getInstance();
    }
}
