// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.scope;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.spi.workflows.HandleContext;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.function.Predicate;

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
     * Returns a predicate that tests whether a given key structure has a valid signature for this strategy
     * with the given {@link HandleContext} and, if applicable, the key used by the sender of an
     * {@link HederaFunctionality#ETHEREUM_TRANSACTION}.
     * @param context the context in which this strategy is being used
     * @param maybeEthSenderKey the key that is the sender of the EVM message, if applicable
     * @return a predicate that tests whether a given key structure has a valid signature in this context
     */
    default Predicate<Key> asSignatureTestIn(
            @NonNull final HandleContext context, @Nullable final Key maybeEthSenderKey) {
        requireNonNull(context);
        final var callback = asPrimitiveSignatureTestIn(context, maybeEthSenderKey);
        return key -> context.keyVerifier()
                .verificationFor(key, (k, v) -> callback.test(k))
                .passed();
    }

    /**
     * Returns a predicate that tests whether a given primitive key has a valid signature for this strategy
     * with the given {@link HandleContext} and, if applicable, the key used by the sender of an
     * {@link HederaFunctionality#ETHEREUM_TRANSACTION}.
     *
     * @param context the context in which this strategy is being used
     * @param maybeEthSenderKey the key that is the sender of the EVM message, if applicable
     * @return a predicate that tests whether a given primitive key has a valid signature in this context
     */
    default Predicate<Key> asPrimitiveSignatureTestIn(
            @NonNull final HandleContext context, @Nullable final Key maybeEthSenderKey) {
        requireNonNull(context);
        return key -> {
            requireNonNull(key);
            return switch (decideForPrimitive(key)) {
                case VALID -> true;
                case INVALID -> false;
                    // Note the Ethereum sender's key has necessarily signed
                case DELEGATE_TO_CRYPTOGRAPHIC_VERIFICATION -> Objects.equals(key, maybeEthSenderKey)
                        || context.keyVerifier().verificationFor(key).passed();
            };
        };
    }
}
