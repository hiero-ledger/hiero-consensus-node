// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.utilops.mod;

import com.google.protobuf.Descriptors;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A strategy for determining whether a field is one of the modification strategy's
 * target; and if so, how it should be modified.
 *
 * @param <T> the type of modifications used by the strategy
 */
public interface ModificationStrategy<T> {
    /**
     * Returns whether the given field should be modified.
     *
     * @param fieldDescriptor the field descriptor
     * @param value the value of the field
     * @return whether the field should be modified
     */
    boolean hasTarget(@NonNull Descriptors.FieldDescriptor fieldDescriptor, @NonNull Object value);

    /**
     * Returns a modification for the given field.
     *
     * @param targetField the field descriptor
     * @param encounterIndex the index of the encounter
     * @return the modification
     */
    @NonNull
    T modificationForTarget(@NonNull TargetField targetField, int encounterIndex);
}
