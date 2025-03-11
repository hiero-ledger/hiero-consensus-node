// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft;

import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class implements a data structure similar to {@link NftLedger} using
 * off the shelf java components. Although it is much slower than our FC data structures, it is much
 * simpler and easy to verify.
 */
public class ReferenceNftLedger {

    private final Map<NftId, Nft> tokenMap;
    private final Map<MapKey, Set<NftId>> ownershipMap;

    /**
     * The fraction, out of 1.0, of tokens to track.
     */
    private double fractionToTrack;

    /**
     * Track any token with a hash whose absolute value does not exceed this threshold.
     * Precomputed for performance.
     */
    private int fractionToTrackThreshold;

    /**
     * A prime number.
     */
    private static final long FRACTION_TO_TRACK_MULTIPLICAND = 100_003;

    /**
     * A prime number.
     */
    private static final int FRACTION_TO_TRACK_MODULUS = 10_007;

    private static final double DEFAULT_FRACTION_TO_TRACK = 0.0;

    /**
     * Construct a new NFT reference ledger that tracks each token.
     */
    public ReferenceNftLedger() {
        this(DEFAULT_FRACTION_TO_TRACK);
    }

    /**
     * Construct a new NFT reference ledger.
     *
     * @param fractionToTrack
     * 		a number between 0.0 and 1.0. At 0.0 no tokens are tracked, at 0.5 half of tokens are tracked, and
     * 		at 1.0 all tokens are tracked, etc. When there are a large number of tokens or if performance is critical,
     * 		tracking a smaller fraction of tokens may be desirable.
     */
    public ReferenceNftLedger(final double fractionToTrack) {
        tokenMap = new HashMap<>();
        ownershipMap = new HashMap<>();
        setFractionToTrack(null, fractionToTrack);
    }

    private ReferenceNftLedger(final ReferenceNftLedger that) {
        this(that.fractionToTrack);

        for (final NftId nftId : that.tokenMap.keySet()) {
            this.tokenMap.put(nftId, that.tokenMap.get(nftId).copy());
        }

        for (final MapKey mapKey : that.ownershipMap.keySet()) {
            final Set<NftId> setCopy = new HashSet<>();
            for (final NftId nftId : that.ownershipMap.get(mapKey)) {
                setCopy.add(nftId);
            }
            this.ownershipMap.put(mapKey.copy(), setCopy);
        }
    }

    /**
     * Update the fraction of tokens that are tracked by this data structure. Requires entire structure to be rebuilt.
     *
     * @param fullLedger
     * 		the ledger to track
     * @param fractionToTrack
     * 		the fraction of nodes to track, a number between 0 and 1.
     */
    public void setFractionToTrack(final NftLedger fullLedger, final double fractionToTrack) {

        if (fractionToTrack == this.fractionToTrack) {
            // We are tracking the same fraction as before
            return;
        }

        this.fractionToTrack = fractionToTrack;
        if (fractionToTrack > 1.0) {
            throw new IllegalArgumentException("fraction must not exceed 1.0");
        } else if (fractionToTrack < 0) {
            throw new IllegalArgumentException("minimum fraction to track is 0.0");
        }
        fractionToTrackThreshold = (int) (fractionToTrack * FRACTION_TO_TRACK_MODULUS);

        reload(fullLedger);
    }

    /**
     * Clear current data and reload it from the full ledger.
     *
     * @param fullLedger
     * 		the ledger containing all tokens
     */
    public void reload(final NftLedger fullLedger) {
        clear();

        if (fullLedger == null || fractionToTrack == 0.0) {
            // No data to track
            return;
        }

        for (final NftId nftId : fullLedger.getTokenIdToToken().keySet()) {
            if (isTokenTracked(nftId)) {
                final Nft nftCopy = fullLedger.getTokenIdToToken().get(nftId).deepCopy();
                final MapKey mapKeyCopy = nftCopy.getMapKey().copy();
                mintToken(mapKeyCopy, nftId, nftCopy);
            }
        }
    }

    /**
     * Delete all data in this structure.
     */
    public void clear() {
        tokenMap.clear();
        ownershipMap.clear();
    }

    /**
     * Get the token map.
     */
    public Map<NftId, Nft> getTokenMap() {
        return tokenMap;
    }

    /**
     * Get the ownership map.
     */
    public Map<MapKey, Set<NftId>> getOwnershipMap() {
        return ownershipMap;
    }

    /**
     * Check if this ledger is tracking a given token.
     *
     * @param nftId
     * 		the token ID in question
     * @return true if the token is being tracked
     */
    public boolean isTokenTracked(final NftId nftId) {
        return (Math.abs(nftId.hashCode()) * FRACTION_TO_TRACK_MULTIPLICAND) % FRACTION_TO_TRACK_MODULUS
                < fractionToTrackThreshold;
    }

    /**
     * Get the fraction of tokens that are being tracked.
     */
    public double getFractionToTrack() {
        return fractionToTrack;
    }

    /**
     * Associate a given token ID with a token instance and an account.
     */
    public void mintToken(final MapKey key, final NftId nftId, final Nft token) {

        if (!isTokenTracked(nftId)) {
            return;
        }

        if (tokenMap.containsKey(nftId)) {
            // Token is already associated with an account
            return;
        }

        // Associate the token instance with the token ID
        tokenMap.put(nftId, token);

        // Associate the token ID with the owner
        if (!ownershipMap.containsKey(key)) {
            ownershipMap.put(key, new HashSet<>());
        }
        ownershipMap.get(key).add(new NftId(nftId.getShardNum(), nftId.getRealmNum(), nftId.getTokenNum()));
    }

    /**
     * Remove all data concerning a token from this data structure.
     */
    public void burnToken(final NftId nftId) {
        if (!isTokenTracked(nftId)) {
            return;
        }

        final Nft token = tokenMap.remove(nftId);

        if (token == null) {
            // Token has already been burned or never existed
            return;
        }

        final MapKey owner = token.getMapKey();

        final Set<NftId> tokensOwnedByOwner = ownershipMap.get(owner);
        tokensOwnedByOwner.remove(nftId);
        if (tokensOwnedByOwner.isEmpty()) {
            ownershipMap.remove(owner);
        }
    }

    /**
     * Transfer ownership of a token from one account to another.
     */
    public void transferToken(final NftId nftId, final MapKey toKey) {
        if (!isTokenTracked(nftId)) {
            return;
        }

        final Nft token = tokenMap.get(nftId);

        if (token == null) {
            // Token has already been burned or never existed
            return;
        }

        burnToken(nftId);
        token.setMapKey(new MapKey(toKey.getShardId(), toKey.getRealmId(), toKey.getAccountId()));
        mintToken(toKey, nftId, token);
    }

    /**
     * Make a deep copy of this object.
     */
    public ReferenceNftLedger copy() {
        return new ReferenceNftLedger(this);
    }

    /**
     * Validate that a given {@link NftLedger} contains the same data as this reference ledger.
     * Only tokens that are tracked are verified.
     *
     * @param ledger
     * 		a ledger that should contain
     */
    public void assertValidity(final NftLedger ledger) {
        // Validate the token map
        for (final NftId tokenId : tokenMap.keySet()) {
            if (!ledger.getTokenIdToToken().containsKey(tokenId)) {
                throw new AssertionError(
                        "Token with ID " + tokenId + " is in the expected ledger but not the actual ledger");
            }
            if (!tokenMap.get(tokenId).equals(ledger.getTokenIdToToken().get(tokenId))) {
                throw new AssertionError(
                        "Token " + tokenMap.get(tokenId) + " in the expected ledger does not match token "
                                + ledger.getTokenIdToToken().get(tokenId) + " in the actual ledger");
            }
        }

        // Validate ownership info
        for (final MapKey ownerId : ownershipMap.keySet()) {
            final List<NftId> ownedTokensList = ledger.getNftAccounts().getList(ownerId);
            if (ownedTokensList == null) {
                throw new AssertionError(
                        "Account " + ownerId + " owns tokens in the expected ledger but not in the actual ledger");
            }
            final HashSet<NftId> ownedTokensSet = new HashSet<>(ownedTokensList);

            for (final NftId nftId : ownershipMap.get(ownerId)) {
                if (!ownedTokensSet.contains(nftId)) {
                    throw new AssertionError("Token " + nftId + " is owned by " + ownerId
                            + " in the expected ledger but not in the actual ledger");
                }
            }
        }
    }
}
