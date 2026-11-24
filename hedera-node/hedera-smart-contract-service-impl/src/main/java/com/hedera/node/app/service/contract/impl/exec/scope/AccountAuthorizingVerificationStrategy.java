// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.key.KeyRequirement;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Binds an invocation's account authority to a canonical identity independently of key activation.
 * The identity must be resolved from the trusted frame's authorizing address, never from a key
 * match or a synthetic transaction's payer field.
 *
 * @param accountId the canonical account authorized by the invocation
 * @param primitiveStrategy the rules for evaluating actual primitive keys
 */
public record AccountAuthorizingVerificationStrategy(
        @NonNull AccountID accountId, @NonNull VerificationStrategy primitiveStrategy) implements VerificationStrategy {
    public AccountAuthorizingVerificationStrategy {
        requireNonNull(accountId);
        requireNonNull(primitiveStrategy);
        if (!accountId.hasAccountNum()) {
            throw new IllegalArgumentException("Account authority requires a canonical numeric account ID");
        }
    }

    @Override
    public boolean authorizesAccount(@NonNull final AccountID requiredAccountId) {
        return accountId.equals(requireNonNull(requiredAccountId));
    }

    @Override
    public Decision decideForPrimitive(@NonNull final Key key) {
        return primitiveStrategy.decideForPrimitive(key);
    }

    @Override
    public Decision decideForPrimitive(@NonNull final KeyRequirement requirement, @NonNull final Key key) {
        return primitiveStrategy.decideForPrimitive(requirement, key);
    }

    /**
     * Adds primitive-key activation rules while preserving only the original account authority.
     * In particular, legacy contract activations do not acquire a self-account exemption.
     *
     * @param base the invocation's original strategy
     * @param additional the additional primitive-key rules
     * @return the combined strategy with the original authority, if any
     */
    public static VerificationStrategy withAdditionalPrimitiveStrategy(
            @NonNull final VerificationStrategy base, @NonNull final VerificationStrategy additional) {
        requireNonNull(base);
        requireNonNull(additional);
        if (base instanceof AccountAuthorizingVerificationStrategy scoped) {
            return new AccountAuthorizingVerificationStrategy(
                    scoped.accountId(), new EitherOrVerificationStrategy(scoped.primitiveStrategy(), additional));
        }
        return new EitherOrVerificationStrategy(base, additional);
    }
}
