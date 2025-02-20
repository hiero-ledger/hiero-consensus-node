// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.context.internal;

import com.swirlds.base.context.Context;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The ThreadLocalContext is a {@link Context} implementation that defined as a singleton and is used to store
 * information that is local to the current thread.
 *
 * @see GlobalContext
 * @see Context
 */
public final class ThreadLocalContext implements Context {

    private static final class InstanceHolder {
        private static final ThreadLocalContext INSTANCE = new ThreadLocalContext();
    }

    private final ThreadLocal<Map<String, String>> contextThreadLocal;

    /**
     * private constructor to prevent instantiation.
     */
    private ThreadLocalContext() {
        this.contextThreadLocal = new ThreadLocal<>();
    }

    @Override
    public AutoCloseable add(@NonNull String key, @NonNull String value) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(value, "value must not be null");

        Map<String, String> contextMap = contextThreadLocal.get();
        if (contextMap == null) {
            contextMap = new HashMap<>();
            contextThreadLocal.set(contextMap);
        }
        contextMap.put(key, value);
        return () -> remove(key);
    }

    @Override
    public void remove(@NonNull String key) {
        Objects.requireNonNull(key, "key must not be null");
        Map<String, String> contextMap = contextThreadLocal.get();
        if (contextMap != null) {
            contextMap.remove(key);
        }
    }

    /**
     * Clears the context map for the current thread.
     */
    public void clear() {
        Map<String, String> contextMap = contextThreadLocal.get();
        if (contextMap != null) {
            contextMap.clear();
        }
    }

    /**
     * Returns the singleton instance of the {@link ThreadLocalContext}.
     *
     * @return the singleton instance of the {@link ThreadLocalContext}
     */
    @NonNull
    public static ThreadLocalContext getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * returns the content of the context of the current thread as an immutable map. This method should only be used by
     * the base apis.
     *
     * @return the content of the context of the current thread as an immutable map
     */
    @NonNull
    public static Map<String, String> getContextMap() {
        final Map<String, String> current = InstanceHolder.INSTANCE.contextThreadLocal.get();
        if (current != null) {
            return Collections.unmodifiableMap(current);
        }
        return Map.of();
    }
}
