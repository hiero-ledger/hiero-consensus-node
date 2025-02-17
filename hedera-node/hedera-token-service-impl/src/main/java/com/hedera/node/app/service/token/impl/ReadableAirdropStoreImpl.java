/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

import static com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema.AIRDROPS_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.token.ReadableAirdropStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Default implementation of {@link ReadableAirdropStore}.
 */
public class ReadableAirdropStoreImpl implements ReadableAirdropStore {
    /** The underlying data storage class that holds the airdrop data. */
    private final ReadableKVState<PendingAirdropId, AccountPendingAirdrop> readableAirdropState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableAirdropStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableAirdropStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        this.entityCounters = entityCounters;
        requireNonNull(states);
        this.readableAirdropState = states.get(AIRDROPS_KEY);
    }

    /** {@inheritDoc} */
    @Override
    public boolean exists(@NonNull final PendingAirdropId airdropId) {
        return readableAirdropState.contains(requireNonNull(airdropId));
    }

    /** {@inheritDoc} */
    @Override
    @Nullable
    public AccountPendingAirdrop get(@NonNull final PendingAirdropId airdropId) {
        requireNonNull(airdropId);
        return readableAirdropState.get(airdropId);
    }

    /** {@inheritDoc} */
    @Override
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.AIRDROP);
    }
}
