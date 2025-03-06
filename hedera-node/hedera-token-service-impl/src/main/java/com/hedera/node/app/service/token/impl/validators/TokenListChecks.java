// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.validators;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TokenID;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashSet;
import java.util.List;

/**
 * Utility methods for checking the validity of {@link TokenID} lists.
 */
public final class TokenListChecks {

    private TokenListChecks() {
        throw new UnsupportedOperationException("Utility class only");
    }

    /**
     * Verifies that a list of {@link TokenID}s contains no duplicates.
     *
     * @param tokens the list of {@link TokenID}s to check
     * @return {@code true} if the list contains no duplicates, {@code false} otherwise
     * @throws NullPointerException if {@code tokens} is {@code null}
     */
    public static boolean repeatsItself(@NonNull final List<TokenID> tokens) {
        requireNonNull(tokens);
        return new HashSet<>(tokens).size() < tokens.size();
    }
}
