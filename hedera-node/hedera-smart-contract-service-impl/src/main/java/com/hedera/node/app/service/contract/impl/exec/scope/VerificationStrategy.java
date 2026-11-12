// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.node.app.spi.key.KeyRequirement;
import com.hedera.node.app.spi.key.RequirementVerifier;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * A strategy interface to allow a dispatcher to optionally set the verification status of a
 * "simple" {@link Key.KeyOneOfType#CONTRACT_ID},
 * {@link Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID}, or
 * even cryptographic key.
 *
 * <p>The strategy has the option to delegate back to the cryptographic verifications
 * already computed by the app in pre-handle and/or handle workflows by returning
 * {@link Decision#DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION}.
 *
 * <p>Because it possible for the {@code tokenTransfer()} system contract to need to amend
 * its dispatched transaction based on the results of signature verifications, the strategy
 * also has the option to return an amended transaction body when this occurs.
 */
public interface VerificationStrategy {
    enum Decision {
        VALID,
        INVALID,
        DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION
    }

    /**
     * Returns a decision on whether to verify the signature of a primitive key. Note
     * this signature may be implicit in the case of a {@link Key.KeyOneOfType#CONTRACT_ID}
     * or {@link Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID}; such keys have active
     * signatures based on the sender address of the EVM message frame.
     *
     * <p>Recall a <i>primitive</i> key is one of the below key types:
     * <ul>
     *     <li>{@link Key.KeyOneOfType#CONTRACT_ID}</li>
     *     <li>{@link Key.KeyOneOfType#DELEGATABLE_CONTRACT_ID}</li>
     *     <li>{@link Key.KeyOneOfType#ED25519}</li>
     *     <li>{@link Key.KeyOneOfType#ECDSA_SECP256K1}</li>
     * </ul>
     *
     * @param key the key whose signature is to be verified
     * @return a decision on whether to verify the signature, or delegate back to the crypto engine results
     */
    Decision decideForPrimitive(@NonNull Key key);

    /**
     * Evaluates a primitive while retaining the authority that caused it to be required.
     * Existing strategies apply their primitive rules independently of that authority.
     *
     * @param requirement the originating requirement
     * @param key the primitive in the requirement's key tree
     * @return the primitive verification decision
     */
    default Decision decideForPrimitive(@NonNull final KeyRequirement requirement, @NonNull final Key key) {
        requireNonNull(requirement);
        return decideForPrimitive(key);
    }

    /**
     * Whether this invocation itself supplies the given account's authority. This never implies
     * a cryptographic signature by that account's key, or authority over another account using it.
     *
     * @param accountId the canonical account whose authority is required
     * @return whether execution authorizes this account
     */
    default boolean authorizesAccount(@NonNull final AccountID accountId) {
        requireNonNull(accountId);
        return false;
    }

    /**
     * Evaluates complete requirements, preserving identity through alternatives and key trees.
     * Account authority is checked before traversing the key, so even an empty or compound
     * admin key is never promoted into cryptographic evidence.
     *
     * <p>The key structure is traversed here rather than through the handle context's verifier,
     * because a dispatch's verifier may treat its (non-existent) signatures as all-authorizing;
     * relying on it would let every primitive pass without applying this strategy's rules. Only
     * an individual {@link Decision#DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION} primitive consults the
     * context's verifier, which preserves the pre-existing cryptographic-delegation behavior.
     *
     * @param context the handle context
     * @param maybeEthSenderKey the Ethereum sender's verified key, if applicable
     * @return the complete requirement verifier
     */
    default RequirementVerifier asRequirementTestIn(
            @NonNull final HandleContext context, @Nullable final Key maybeEthSenderKey) {
        requireNonNull(context);
        return new RequirementVerifier() {
            @Override
            public boolean test(@NonNull final KeyRequirement requirement) {
                requireNonNull(requirement);
                if (requirement instanceof KeyRequirement.AnyOf anyOf) {
                    return anyOf.alternatives().stream().anyMatch(this::test);
                }
                if (requirement instanceof KeyRequirement.AccountKey accountKey
                        && authorizesAccount(accountKey.accountId())) {
                    return true;
                }
                return passesFor(requirement, requirement.key());
            }

            private boolean passesFor(@NonNull final KeyRequirement requirement, @NonNull final Key key) {
                return switch (key.key().kind()) {
                    case KEY_LIST -> {
                        final var keys = key.keyListOrThrow().keys();
                        yield !keys.isEmpty() && keys.stream().allMatch(child -> passesFor(requirement, child));
                    }
                    case THRESHOLD_KEY -> {
                        final var thresholdKey = key.thresholdKeyOrThrow();
                        final var keys =
                                thresholdKey.keysOrElse(KeyList.DEFAULT).keys();
                        final var threshold = Math.max(1, Math.min(thresholdKey.threshold(), keys.size()));
                        yield keys.stream()
                                        .filter(child -> passesFor(requirement, child))
                                        .count()
                                >= threshold;
                    }
                    default ->
                        switch (decideForPrimitive(requirement, key)) {
                            case VALID -> true;
                            case INVALID -> false;
                            case DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION ->
                                Objects.equals(key, maybeEthSenderKey)
                                        || context.keyVerifier()
                                                .verificationFor(key)
                                                .passed();
                        };
                };
            }
        };
    }
}
