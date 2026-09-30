// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.spi;

import com.hedera.pbj.runtime.CopyBuilderTracked;
import edu.umd.cs.findbugs.annotations.Nullable;

/** PBJ provenance integration. State storage retains untracked values; listeners receive the tracked next value. */
public final class CopyBuilderTracking {
    private CopyBuilderTracking() {}

    /** Whether the value has a tracked origin identical to the non-null prior instance. */
    public static boolean hasOrigin(@Nullable final Object value, @Nullable final Object prior) {
        return prior != null && value instanceof CopyBuilderTracked<?> tracked && tracked.$copyBuilderOrigin() == prior;
    }

    /** Whether a protobuf field is a candidate; untracked values must include every field. */
    public static boolean fieldMayHaveChanged(@Nullable final Object value, final int fieldNumber) {
        return !(value instanceof CopyBuilderTracked<?> tracked)
                || tracked.$copyBuilderOrigin() == null
                || tracked.$copyBuilderFieldChanged(fieldNumber);
    }

    /** Remove shallow provenance before storing a value, preserving its immutable field values. */
    @Nullable
    public static <T> T untracked(@Nullable final T value) {
        return CopyBuilderTracked.untracked(value);
    }
}
