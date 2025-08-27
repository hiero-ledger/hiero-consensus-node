// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

/**
 * Each RunningHashable instance contains a RunningHash instance, which encapsulates a Hash object
 * which denotes a running Hash calculated from all RunningHashable in history up to this RunningHashable instance
 */
public interface RunningHashable extends Hashable {

    /**
     * Gets the current {@link RunningHash} instance associated with this object. This method should always return an
     * instance of the {@link RunningHash} class and should never return a {@code null} value.
     *
     * @return the attached {@code RunningHash} instance.
     */
    RunningHash getRunningHash();
}
