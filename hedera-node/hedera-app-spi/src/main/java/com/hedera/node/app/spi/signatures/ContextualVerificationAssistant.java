// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.signatures;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.key.KeyRequirement;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Observes a primitive key's verification without losing the authority that requires it. */
@FunctionalInterface
public interface ContextualVerificationAssistant {
    /**
     * Determines whether a primitive key satisfies its originating requirement.
     *
     * @param requirement the originating account or explicit-key requirement
     * @param primitiveKey the primitive key being evaluated
     * @param verification the cryptographic verification evidence
     * @return whether the primitive key is authorized in this requirement
     */
    boolean test(
            @NonNull KeyRequirement requirement,
            @NonNull Key primitiveKey,
            @NonNull SignatureVerification verification);
}
