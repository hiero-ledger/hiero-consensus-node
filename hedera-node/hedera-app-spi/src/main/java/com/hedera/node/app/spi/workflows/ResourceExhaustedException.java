// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link HandleException} specialization that indicates that a resource limit has been exceeded.
 */
public class ResourceExhaustedException extends HandleException {
    public ResourceExhaustedException(@NonNull final ResponseCodeEnum status) {
        super(requireNonNull(status));
    }

    /**
     * Asserts that a resource is not exhausted.
     *
     * @param flag the flag to check
     * @param code the status to throw if the flag is false
     */
    public static void validateResource(final boolean flag, @NonNull final ResponseCodeEnum code) {
        requireNonNull(code);
        if (!flag) {
            throw new ResourceExhaustedException(code);
        }
    }
}
