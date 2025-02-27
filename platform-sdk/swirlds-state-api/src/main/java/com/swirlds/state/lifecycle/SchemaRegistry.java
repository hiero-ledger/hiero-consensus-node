// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collection;

/**
 * Provided by the application to the {@link Service}, the {@link SchemaRegistry} is used by the
 * {@link Service} to register all of its {@link Schema}s.
 */
public interface SchemaRegistry {
    /**
     * Register the given array of {@link Schema}s.
     *
     * @param schemas The schemas to register. Cannot contain nulls or be null.
     * @return a reference to this registry instance
     */
    default SchemaRegistry registerAll(@NonNull Schema... schemas) {
        for (final var s : schemas) {
            register(s);
        }

        return this;
    }

    /**
     * Register the given {@link Schema}. {@link Schema}s do not need to be registered in order.
     *
     * @param schema The {@link Schema} to register.
     * @return a reference to this registry instance
     */
    SchemaRegistry register(@NonNull Schema schema);

    /**
     * Register the given array of {@link Schema}s.
     *
     * @param schemas The schemas to register. Cannot contain nulls or be null.
     * @return a reference to this registry instance
     */
    default SchemaRegistry registerAll(@NonNull Collection<Schema> schemas) {
        for (final var s : schemas) {
            register(s);
        }

        return this;
    }
}
