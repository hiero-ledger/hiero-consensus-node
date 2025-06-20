// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.spec.dsl;

import com.hedera.services.bdd.spec.HapiSpec;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Defines a type able to register information (e.g. entity ids, keys, etc.) with a {@link HapiSpec}.
 */
@FunctionalInterface
public interface SpecEntityRegistrar {
    void registerWith(@NonNull HapiSpec spec);
}
