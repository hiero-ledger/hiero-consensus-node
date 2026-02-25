// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static com.hedera.node.app.service.token.impl.WritableAccountStore.requireNotDefault;
import static com.hedera.node.app.service.token.impl.WritableTokenStore.requireNotDefault;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;
import java.util.Set;

/**
 * Provides write methods for modifying underlying data storage mechanisms for
 * working with TokenRelations.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 * This class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableTokenRelationStore extends ReadableTokenRelationStoreImpl {
    /** The underlying data storage class that holds the token data. */
    private final WritableKVState<EntityIDPair, TokenRelation> tokenRelState;

    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableTokenRelationStore} instance.
     *
     * @param states The state to use.
     */
    public WritableTokenRelationStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.tokenRelState = requireNonNull(states).get(V0490TokenSchema.TOKEN_RELS_STATE_ID);
        this.entityCounters = entityCounters;
    }

    /**
     * Persists an updated {@link TokenRelation} into the state.
     *
     * @param tokenRelation - the tokenRelation to be persisted
     */
    public void put(@NonNull final TokenRelation tokenRelation) {
        requireNotDefault(tokenRelation.accountIdOrThrow());
        requireNotDefault(tokenRelation.tokenIdOrThrow());
        tokenRelState.put(
                EntityIDPair.newBuilder()
                        .accountId(tokenRelation.accountId())
                        .tokenId(tokenRelation.tokenId())
                        .build(),
                Objects.requireNonNull(tokenRelation));
    }

    /**
     * Persists a new {@link TokenRelation} into the state and increments the entity counter for token relations.
     * @param tokenRelation the token relation to be persisted
     */
    public void putAndIncrementCount(@NonNull final TokenRelation tokenRelation) {
        put(tokenRelation);
        entityCounters.incrementEntityTypeCount(EntityType.TOKEN_ASSOCIATION);
    }

    /**
     * Removes a {@link TokenRelation} from the state.
     *
     * @param tokenRelation the {@code TokenRelation} to be removed
     */
    public void remove(@NonNull final TokenRelation tokenRelation) {
        tokenRelState.remove(EntityIDPair.newBuilder()
                .accountId(tokenRelation.accountId())
                .tokenId(tokenRelation.tokenId())
                .build());
        entityCounters.decrementEntityTypeCounter(EntityType.TOKEN_ASSOCIATION);
    }

    /**
     * Gets the original value associated with the given tokenRelation before any modifications were made to
     * it. The returned value will be {@code null} if the tokenRelation does not exist.
     *
     * @param accountId The accountId of tokenRelation.
     * @param tokenId The tokenId of tokenRelation.
     * @return The original value, or null if there is no such tokenRelation in the state
     */
    @Nullable
    public TokenRelation getOriginalValue(@NonNull final AccountID accountId, @NonNull final TokenID tokenId) {
        requireNonNull(accountId);
        requireNonNull(tokenId);
        return tokenRelState.getOriginalValue(
                EntityIDPair.newBuilder().accountId(accountId).tokenId(tokenId).build());
    }

    /**
     * Returns the set of token relations modified in the state.
     * @return the set of token relations modified in existing state
     */
    public Set<EntityIDPair> modifiedTokens() {
        return tokenRelState.modifiedKeys();
    }
}
