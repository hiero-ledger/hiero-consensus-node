// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.common;

import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Optional;

/**
 * Strategy interface for translating {@link HtsCallAttempt}s into {@link Call}s.
 * @param <T> the type of the call translator
 */
public interface CallTranslator<T> {
    /**
     * Tries to translate the given {@code attempt} into a {@link Call}, returning null if the call
     * doesn't match the target type of this translator.
     *
     * @param attempt the attempt to translate
     * @return the translated {@link Call}
     */
    @Nullable
    Call translateCallAttempt(@NonNull T attempt);

    /**
     * Returns the SystemContractMethod for this attempt's selector, if the selector is known.
     * @param attempt the selector to match (in the Attempt)
     * @return the SystemContractMethod for the attempt (or it can be empty if unknown)
     */
    @NonNull
    Optional<SystemContractMethod> identifyMethod(@NonNull T attempt);

    /**
     * Returns a call from the given attempt.
     *
     * @param attempt the attempt to get the call from
     * @return a call from the given attempt
     */
    Call callFrom(@NonNull T attempt);

    /** Returns a call from the given attempt
     *
     * @param attempt the attempt to get the call from
     * @param systemContractMethod system contract method the attempt is calling
     * @return a call from the given attempt
     */
    default Call callFrom(@NonNull final T attempt, @NonNull final SystemContractMethod systemContractMethod) {
        final var call = callFrom(attempt);
        call.setSystemContractMethod(systemContractMethod);
        return call;
    }
}
