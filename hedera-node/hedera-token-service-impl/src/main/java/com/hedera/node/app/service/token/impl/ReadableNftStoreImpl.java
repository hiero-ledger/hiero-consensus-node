/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.state.token.Nft;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableNftStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.schemas.V0490TokenSchema;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Default implementation of {@link ReadableTokenStore}.
 */
public class ReadableNftStoreImpl implements ReadableNftStore {
    /** The underlying data storage class that holds the token data. */
    private final ReadableKVState<NftID, Nft> nftState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableNftStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableNftStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        this.entityCounters = requireNonNull(entityCounters);
        this.nftState = states.get(V0490TokenSchema.NFTS_KEY);
    }

    @Override
    @Nullable
    public Nft get(@NonNull final NftID nftId) {
        requireNonNull(nftId);
        return nftState.get(nftId);
    }

    /**
     * Returns the number of nfts in the state.
     * @return the number of nfts in the state
     */
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.NFT);
    }

    @Override
    public void warm(@NonNull final NftID nftID) {
        nftState.warm(nftID);
    }
}
