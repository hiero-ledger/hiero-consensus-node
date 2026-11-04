// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

import static java.util.Collections.unmodifiableSortedSet;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.hapi.utils.keys.KeyComparator;
import com.hedera.node.app.spi.signatures.ContextualVerificationAssistant;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import com.hedera.node.app.spi.signatures.VerificationAssistant;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Helper class that contains all functionality for verifying signatures during handle.
 */
public interface KeyVerifier {
    SortedSet<Key> NO_AUTHORIZING_KEYS = unmodifiableSortedSet(new TreeSet<>(new KeyComparator()));

    /**
     * Gets the {@link SignatureVerification} for the given key. If this key was not provided during pre-handle, then
     * there will be no corresponding {@link SignatureVerification}. If the key was provided during pre-handle, then the
     * corresponding {@link SignatureVerification} will be returned with the result of that verification operation.
     *
     * <p>The signatures of required keys are guaranteed to be verified. Optional signatures may still be in the
     * process of being verified (and therefore may time out). The timeout can be configured via the configuration
     * {@code hedera.workflow.verificationTimeoutMS}
     *
     * @param key the key to get the verification for
     * @return the verification for the given key
     * @throws NullPointerException if {@code key} is {@code null}
     */
    @NonNull
    SignatureVerification verificationFor(@NonNull Key key);

    /**
     * Gets the {@link SignatureVerification} for the given key. If this key was not provided during pre-handle, then
     * there will be no corresponding {@link SignatureVerification}. If the key was provided during pre-handle, then the
     * corresponding {@link SignatureVerification} will be returned with the result of that verification operation.
     * Additionally, the VerificationAssistant provided may modify the result for "primitive", "Contract ID", or
     * "Delegatable Contract ID" keys, and will be called to observe and reply for each such key as it is processed.
     *
     * <p>The signatures of required keys are guaranteed to be verified. Optional signatures may still be in the
     * process of being verified (and therefore may time out). The timeout can be configured via the configuration
     * {@code hedera.workflow.verificationTimeoutMS}
     *
     * @param key the key to get the verification for
     * @param callback a VerificationAssistant callback function that will observe each "primitive", "Contract ID", or
     * "Delegatable Contract ID" key and return a boolean indicating if the given key should be considered valid.
     * @return the verification for the given key
     */
    @NonNull
    SignatureVerification verificationFor(@NonNull Key key, @NonNull VerificationAssistant callback);

    /**
     * Verifies a requirement without treating its key as proof of any other identity's authority.
     * Ordinary cryptographic verification is independent of scope; contextual verifiers can override
     * this method to authorize an individual account requirement from execution context.
     *
     * @param requirement the authority and key required by the operation
     * @return the verification of the requirement
     */
    @NonNull
    default SignatureVerification verificationFor(@NonNull final KeyRequirement requirement) {
        requireNonNull(requirement);
        if (requirement instanceof KeyRequirement.AnyOf anyOf) {
            return requirementVerification(
                    requirement, anyOf.alternatives().stream().anyMatch(r -> verificationFor(r)
                            .passed()));
        }
        return verificationFor(requirement.key());
    }

    /**
     * Evaluates every primitive with its originating requirement. Composed alternatives retain
     * their own identities, while every leaf of a compound key retains the same requirement.
     *
     * @param requirement the authority and key required by the operation
     * @param callback the contextual primitive verification assistant
     * @return the verification of the requirement
     */
    @NonNull
    default SignatureVerification verificationFor(
            @NonNull final KeyRequirement requirement, @NonNull final ContextualVerificationAssistant callback) {
        requireNonNull(requirement);
        requireNonNull(callback);
        if (requirement instanceof KeyRequirement.AnyOf anyOf) {
            boolean passed = false;
            for (final var alternative : anyOf.alternatives()) {
                // Visit all alternatives because the assistant may observe or collect authorizing keys.
                passed |= verificationFor(alternative, callback).passed();
            }
            return requirementVerification(requirement, passed);
        }
        return verificationFor(requirement.key(), (key, verification) -> callback.test(requirement, key, verification));
    }

    private static SignatureVerification requirementVerification(
            final KeyRequirement requirement, final boolean passed) {
        return new SignatureVerification() {
            @Override
            public Key key() {
                return requirement.key();
            }

            @Override
            public boolean passed() {
                return passed;
            }
        };
    }

    /**
     * If this verifier was authorized by one or more simple keys---for example, via cryptographic signature on a
     * transaction submitted via HAPI; or via the action of a contract inside the EVM---returns the set of these
     * authorizing keys, ordered by the {@link KeyComparator}.
     * <p>
     * Account-scoped execution authority must not add the account's cryptographic admin key to this set.
     * Default is an empty set, for verifiers whose authorization is not derived from a legible set of simple keys.
     * @return any simple keys that authorized this verifier
     */
    default SortedSet<Key> authorizingSimpleKeys() {
        return NO_AUTHORIZING_KEYS;
    }
}
