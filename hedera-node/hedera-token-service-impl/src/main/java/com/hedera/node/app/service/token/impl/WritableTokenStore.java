// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.ids.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Set;

/**
 * Provides write methods for modifying underlying data storage mechanisms for
 * working with Tokens.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 * This class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableTokenStore extends ReadableTokenStoreImpl {
    /** The underlying data storage class that holds the token data. */
    private final WritableKVState<TokenID, Token> tokenState;

    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableTokenStore} instance.
     *
     * @param states The state to use.
     */
    public WritableTokenStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.tokenState = states.get(V0490TokenSchema.TOKENS_STATE_ID);
        this.entityCounters = entityCounters;
    }

    /**
     * Persists an updated {@link Token} into the state, as well as exporting its ID to the transaction
     * receipt.
     *
     * @param token - the token persisted
     */
    public void put(@NonNull final Token token) {
        Objects.requireNonNull(token);
        requireNotDefault(token.tokenId());
        tokenState.put(token.tokenId(), Objects.requireNonNull(token));
    }

    /**
     * Persists a new {@link Token} into the state. It also increments the entity counts for
     * {@link EntityType#TOKEN}.
     * @param token
     */
    public void putAndIncrementCount(@NonNull final Token token) {
        put(token);
        entityCounters.incrementEntityTypeCount(EntityType.TOKEN);
    }

    /**
     * Returns the number of tokens in the state.
     * @return the number of tokens in the state
     */
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.TOKEN);
    }

    /**
     * Returns the set of tokens modified in existing state.
     * @return the set of tokens modified in existing state
     */
    @NonNull
    public Set<TokenID> modifiedTokens() {
        return tokenState.modifiedKeys();
    }

    /**
     * Gets the original value associated with the given tokenId before any modifications were made to
     * it. The returned value will be {@code null} if the tokenId does not exist.
     *
     * @param tokenId The tokenId.
     * @return The original value, or null if there is no such tokenId in the state
     */
    @Nullable
    public Token getOriginalValue(@NonNull final TokenID tokenId) {
        requireNonNull(tokenId);
        return tokenState.getOriginalValue(tokenId);
    }

    /**
     * Checks if the given tokenId is not the default tokenId. If it is, throws an {@link IllegalArgumentException}.
     * @param tokenId The tokenId to check.
     */
    public static void requireNotDefault(@NonNull final TokenID tokenId) {
        if (tokenId.equals(TokenID.DEFAULT)) {
            throw new IllegalArgumentException("Token ID cannot be default");
        }
    }
}
