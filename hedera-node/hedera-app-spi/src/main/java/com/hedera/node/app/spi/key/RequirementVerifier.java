// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Verifies a complete authorization requirement, including its identity and any alternatives.
 * Unlike a primitive-key predicate, this callback is invoked once for the whole requirement.
 */
@FunctionalInterface
public interface RequirementVerifier {
    /**
     * Evaluates the complete requirement without promoting account authority into signature evidence.
     * @param requirement the originating authority and its key or alternatives
     * @return whether the requirement is authorized in this dispatch
     */
    boolean test(@NonNull KeyRequirement requirement);
}
