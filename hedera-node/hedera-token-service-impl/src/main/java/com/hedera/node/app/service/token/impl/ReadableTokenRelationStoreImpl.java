// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.common.EntityIDPair;
import com.hedera.hapi.node.state.token.TokenRelation;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableTokenRelationStore;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for getting underlying data for working with TokenRelations.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public class ReadableTokenRelationStoreImpl implements ReadableTokenRelationStore {

    /** The underlying data storage class that holds the token data. */
    private final ReadableKVState<EntityIDPair, TokenRelation> readableTokenRelState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableTokenRelationStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableTokenRelationStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        this.readableTokenRelState = requireNonNull(states).get(V0490TokenSchema.TOKEN_RELS_STATE_ID);
        this.entityCounters = requireNonNull(entityCounters);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public TokenRelation get(@NonNull final AccountID accountId, @NonNull final TokenID tokenId) {
        requireNonNull(accountId);
        requireNonNull(tokenId);

        if (AccountID.DEFAULT.equals(accountId) || TokenID.DEFAULT.equals(tokenId)) return null;

        return readableTokenRelState.get(
                EntityIDPair.newBuilder().accountId(accountId).tokenId(tokenId).build());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.TOKEN_ASSOCIATION);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void warm(@NonNull final AccountID accountID, @NonNull final TokenID tokenId) {
        final EntityIDPair key =
                EntityIDPair.newBuilder().accountId(accountID).tokenId(tokenId).build();
        readableTokenRelState.warm(key);
    }
}
