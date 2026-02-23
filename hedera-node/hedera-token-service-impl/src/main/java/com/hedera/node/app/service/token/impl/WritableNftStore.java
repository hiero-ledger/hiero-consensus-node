// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.NftID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.state.token.Nft;
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
 * working with NFTs.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 * This class is not complete, it will be extended with other methods like remove, update etc.,
 */
public class WritableNftStore extends ReadableNftStoreImpl {

    /** The underlying data storage class that holds the NFT data. */
    private final WritableKVState<NftID, Nft> nftState;

    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableNftStore} instance.
     *
     * @param states The state to use.
     */
    public WritableNftStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.nftState = states.get(V0490TokenSchema.NFTS_STATE_ID);
        this.entityCounters = entityCounters;
    }

    /**
     * Persists an updated {@link Nft} into the state, as well as exporting its ID to the transaction
     * receipt.
     *
     * @param nft - the nft to be persisted.
     */
    public void put(@NonNull final Nft nft) {
        Objects.requireNonNull(nft);
        requireNotDefault(nft.nftId());
        nftState.put(nft.nftId(), nft);
    }

    /**
     * Persists a new {@link Nft} into the state. This also increments the entity counts for
     * {@link EntityType#NFT}.
     * @param nft the nft to be persisted
     */
    public void putAndIncrementCount(@NonNull final Nft nft) {
        put(nft);
        entityCounters.incrementEntityTypeCount(EntityType.NFT);
    }

    /**
     * Returns the set of nfts modified in existing state.
     * @return the set of nfts modified in existing state
     */
    @NonNull
    public Set<NftID> modifiedNfts() {
        return nftState.modifiedKeys();
    }

    /**
     * Removes the {@link Nft} with the given serial number.
     *
     * @param serialNum - the combined unique ID of the NFT to remove
     */
    public void remove(final @NonNull NftID serialNum) {
        nftState.remove(requireNonNull(serialNum));
        entityCounters.decrementEntityTypeCounter(EntityType.NFT);
    }

    /**
     * Removes the {@link Nft} with the given serial number.
     *
     * @param tokenId - the token id of the NFT to remove
     * @param serialNum - the serial number of the NFT to remove
     */
    public void remove(final @NonNull TokenID tokenId, final long serialNum) {
        final var nftId =
                NftID.newBuilder().tokenId(tokenId).serialNumber(serialNum).build();
        requireNotDefault(nftId);
        remove(nftId);
    }

    /**
     * Gets the original value associated with the given nftId before any modifications were made to
     * it. The returned value will be {@code null} if the nftId does not exist.
     *
     * @param nftId The nftId. Cannot be null, otherwise an exception is thrown.
     * @return The original value, or null if there is no such nftId in the state
     * @throws NullPointerException if the accountId is null.
     */
    @Nullable
    public Nft getOriginalValue(@NonNull final NftID nftId) {
        requireNonNull(nftId);
        return nftState.getOriginalValue(nftId);
    }

    private void requireNotDefault(@NonNull final NftID nftId) {
        if (nftId.equals(NftID.DEFAULT)) {
            throw new IllegalArgumentException("Nft ID cannot be default");
        }
    }
}
