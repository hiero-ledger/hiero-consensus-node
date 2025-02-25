// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.store.persistence.exception;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Exception that indicates that the searched entity was not found in the system
 */
public class EntityNotFoundException extends RuntimeException {
    private final Class<?> entityType;
    private final String id;

    public EntityNotFoundException(@NonNull final Class<?> entityClass, @NonNull final String id) {
        this.entityType = entityClass;
        this.id = id;
    }

    @NonNull
    public String getEntityType() {
        return entityType.getSimpleName();
    }

    @NonNull
    public String getId() {
        return id;
    }
}
