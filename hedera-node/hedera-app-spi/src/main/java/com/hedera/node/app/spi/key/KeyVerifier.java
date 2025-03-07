// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.key;

import static java.util.Collections.unmodifiableSortedSet;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.hapi.utils.keys.KeyComparator;
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
     * If this verifier was authorized by one or more simple keys---for example, via cryptographic signature on a
     * transaction submitted via HAPI; or via the action of a contract inside the EVM---returns the set of these
     * authorizing keys, ordered by the {@link KeyComparator}.
     * <p>
     * Default is an empty set, for verifiers whose authorization is not derived from a legible set of simple keys.
     * @return any simple keys that authorized this verifier
     */
    default SortedSet<Key> authorizingSimpleKeys() {
        return NO_AUTHORIZING_KEYS;
    }
}
