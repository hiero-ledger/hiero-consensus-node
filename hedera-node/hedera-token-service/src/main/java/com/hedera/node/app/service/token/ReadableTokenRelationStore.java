// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.TokenRelation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for getting underlying data for working with TokenRelations.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public interface ReadableTokenRelationStore {
    /**
     * Returns the {@link TokenRelation} with the given IDs. If no such token relation exists,
     * returns {@code null}
     *
     * @param accountId - the id of the account in the token-relation to be retrieved
     * @param tokenId   - the id of the token in the token-relation to be retrieved
     * @return the token-relation with the given IDs, or {@code null} if no such token-relation exists
     */
    @Nullable
    TokenRelation get(@NonNull AccountID accountId, @NonNull TokenID tokenId);

    /**
     * Returns the number of tokens in the state.
     * @return the number of tokens in the state
     */
    long sizeOfState();

    /**
     * Warms the system by preloading a token relationship into memory
     *
     * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
     *
     * @param accountID the account id
     * @param tokenId the token id
     */
    default void warm(@NonNull final AccountID accountID, @NonNull final TokenID tokenId) {}
}
