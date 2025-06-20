// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * Defines the interface for a single operation in a {@link HapiSpec}.
 */
public interface SpecOperation {
    /**
     * Execute the operation for the given {@link HapiSpec}, returning an optional that
     * contains any failure (include assertion {@link Error}s) that were thrown.
     *
     * @param spec the {@link HapiSpec} to execute the operation for
     * @return an optional containing any failure that was thrown
     */
    Optional<Throwable> execFor(@NonNull HapiSpec spec);
}
