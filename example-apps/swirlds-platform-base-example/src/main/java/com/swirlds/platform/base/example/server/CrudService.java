// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.server;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;

/**
 * A service that supports crud operations for {@code <T>}
 * Unless overridden, all operations throw {@link UnsupportedOperationException}
 * @param <T>
 */
public abstract class CrudService<T> {

    private final Class<T> resultType;

    public CrudService(@NonNull final Class<T> resultType) {
        this.resultType = resultType;
    }

    public void delete(final @NonNull String key) {
        throw new UnsupportedOperationException("Delete Operation not supported");
    }

    public @NonNull T update(final @NonNull String key, final @NonNull T body) {
        throw new UnsupportedOperationException("Update Operation not supported");
    }

    public @NonNull T create(final @NonNull T body) {
        throw new UnsupportedOperationException("Create Operation not supported");
    }

    public @NonNull T retrieve(final @NonNull String key) {
        throw new UnsupportedOperationException("Retrieve Operation not supported");
    }

    public @NonNull List<T> retrieveAll(@NonNull Map<String, String> params) {
        throw new UnsupportedOperationException("Retrieve All Operation not supported");
    }

    public @NonNull Class<T> getResultType() {
        return this.resultType;
    }
}
