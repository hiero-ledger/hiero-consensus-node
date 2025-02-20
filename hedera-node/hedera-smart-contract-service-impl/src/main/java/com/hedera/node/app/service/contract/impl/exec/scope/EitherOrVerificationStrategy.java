// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.INVALID;
import static com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy.Decision.VALID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link VerificationStrategy} that verifies signatures as if both of two contracts are "active; but never
 * using top-level signatures.
 *
 * <p>This is the verification strategy used to support the {@code contracts.keys.legacyActivations} property.
 */
public class EitherOrVerificationStrategy implements VerificationStrategy {
    private final VerificationStrategy firstStrategy;
    private final VerificationStrategy secondStrategy;

    /**
     * @param firstStrategy the first verification strategy to be used
     * @param secondStrategy the second verification strategy to be used
     */
    public EitherOrVerificationStrategy(
            @NonNull final VerificationStrategy firstStrategy, @NonNull final VerificationStrategy secondStrategy) {
        this.firstStrategy = firstStrategy;
        this.secondStrategy = secondStrategy;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns {@link VerificationStrategy.Decision#INVALID} if both strategies agree the
     * key's signature is invalid; {@link VerificationStrategy.Decision#VALID} if either strategy
     * says the key is valid; and {@link VerificationStrategy.Decision#DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION}
     * otherwise. (I.e., if one strategy says the key's signature is invalid but the other allows
     * us to delegate.)
     *
     * @param key the key whose signature is to be verified
     * @return the decision of the strategy
     */
    @Override
    public Decision decideForPrimitive(@NonNull final Key key) {
        requireNonNull(key);
        final var firstDecision = firstStrategy.decideForPrimitive(key);
        if (firstDecision == VALID) {
            return VALID;
        } else {
            final var secondDecision = secondStrategy.decideForPrimitive(key);
            if (secondDecision == VALID) {
                return VALID;
            }
            return secondDecision == INVALID ? firstDecision : secondDecision;
        }
    }
}
