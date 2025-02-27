// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * pending Airdrop states.
 */
public interface ReadableAirdropStore {
    /**
     * Fetches an {@link AccountPendingAirdrop} object from state for given {@link PendingAirdropId}.
     * If the airdrop  could not be fetched because the given airdrop doesn't exist, returns {@code null}.
     *
     * @param airdropId given airdrop id
     * @return {@link AccountPendingAirdrop} object if successfully fetched or {@code null} if the airdrop doesn't exist
     */
    AccountPendingAirdrop get(@NonNull PendingAirdropId airdropId);

    /**
     * Returns whether a given PendingAirdropId exists in state.
     *
     * @param airdropId - the id of the airdrop
     * @return true if the airdrop exists, false otherwise
     */
    boolean exists(@NonNull PendingAirdropId airdropId);

    /**
     * Returns the number of entities in the pending airdrops state.
     * @return the size of the pending airdrops state
     */
    long sizeOfState();

    /**
     * Warms the system by preloading a token into memory
     *
     * <p>The default implementation is empty because preloading data into memory is only used for some implementations.
     *
     * @param airdropId the token id
     */
    default void warm(@NonNull final PendingAirdropId airdropId) {}
}
