// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform.nft;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.merkle.MerkleInternal;
import com.swirlds.common.merkle.impl.PartialBinaryMerkleInternal;
import com.swirlds.common.utility.StopWatch;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.merkle.map.MerkleMap;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This merkle data structure tests a workflow that has similarities to Hedera NFTs. It is, of course, much simpler than
 * what Hedera is doing.
 */
public class NftLedger extends PartialBinaryMerkleInternal implements MerkleInternal {

    private static final Logger logger = LogManager.getLogger(NftLedger.class);

    public static final long CLASS_ID = 0xf74e1ca18516781dL;

    private static class ClassVersion {
        private static final int VERSION_ORIGINAL = 1;
    }

    private static class ChildIndices {
        public static final int NFT_ID_TO_NFT = 0;

        public static final int CHILD_COUNT = 1;
    }

    private final FCOneToManyRelation<MapKey, NftId> nftAccounts;

    public NftLedger() {
        this.nftAccounts = new FCOneToManyRelation<>();
        this.setTokenIdToToken(new MerkleMap<>());
    }

    private NftLedger(final NftLedger nftLedger) {
        super(nftLedger);
        this.nftAccounts = nftLedger.nftAccounts.copy();
        this.setTokenIdToToken(nftLedger.getTokenIdToToken().copy());
        nftLedger.setImmutable(true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getVersion() {
        return ClassVersion.VERSION_ORIGINAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NftLedger copy() {
        return new NftLedger(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean childHasExpectedType(final int index, final long childClassId) {
        if (index == ChildIndices.NFT_ID_TO_NFT) {
            return childClassId == MerkleMap.CLASS_ID;
        }

        return true;
    }

    /**
     * Set the map from token IDs to the token with that ID.
     */
    private void setTokenIdToToken(final MerkleMap<NftId, Nft> tokenIdToToken) {
        setChild(ChildIndices.NFT_ID_TO_NFT, tokenIdToToken);
    }

    /**
     * Get the map from token IDs to the token with that ID.
     */
    public MerkleMap<NftId, Nft> getTokenIdToToken() {
        return getChild(ChildIndices.NFT_ID_TO_NFT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rebuild() {
        final MerkleMap<NftId, Nft> tokenIdToNft = getTokenIdToToken();
        for (final Map.Entry<NftId, Nft> entry : tokenIdToNft.entrySet()) {
            this.nftAccounts.associate(entry.getValue().getMapKey(), entry.getKey());
        }
    }

    @Override
    public synchronized void destroyNode() {
        if (!nftAccounts.isDestroyed()) {
            nftAccounts.release();
        }
    }

    /**
     * Create a new token.
     *
     * @param key   the key of an account that will own the token
     * @param nftId the ID of the token
     * @param token the actual token, expected to already have internal fields populated (e.g. token ID)
     */
    public void mintToken(final MapKey key, final NftId nftId, final Nft token) {
        throwIfImmutable();
        throwIfDestroyed();

        final StopWatch watch = new StopWatch();
        watch.start();
        this.nftAccounts.associate(key, nftId);
        getTokenIdToToken().put(nftId, token);
        watch.stop();
        NftLedgerStatistics.recordMintTokenDuration(watch.getTime(TimeUnit.MICROSECONDS));
        NftLedgerStatistics.addMintTokenOperation();
    }

    /**
     * Destroy a token, removing all references of it from the data structure.
     *
     * @param nftId the ID fo the token to destroy
     */
    public void burnToken(final NftId nftId) {
        throwIfImmutable();
        throwIfDestroyed();

        final StopWatch watch = new StopWatch();
        watch.start();

        final Nft nft = getTokenIdToToken().remove(nftId);
        if (nft == null) {
            logger.error(EXCEPTION.getMarker(), "Failed to burn token with id {} due to null nft", nftId);
            return;
        }

        this.nftAccounts.disassociate(nft.getMapKey(), nftId);
        watch.stop();
        NftLedgerStatistics.recordBurnTokenDuration(watch.getTime(TimeUnit.MICROSECONDS));
        NftLedgerStatistics.addBurnTokenOperation();
    }

    /**
     * Change the ownership of a token.
     *
     * @param nftId the token to transfer
     * @param toKey the ID of the account that will hold the token after the transfer
     */
    public void transferToken(final NftId nftId, final MapKey toKey) {
        throwIfImmutable();
        throwIfDestroyed();

        final StopWatch watch = new StopWatch();
        watch.start();
        final Nft nft = getTokenIdToToken().getForModify(nftId);
        if (nft == null) {
            logger.error(EXCEPTION.getMarker(), "Failed to transfer token with id {} due to null nft", nftId);
            return;
        }

        final boolean wasAssociated = nftAccounts.disassociate(nft.getMapKey(), nftId);
        if (!wasAssociated) {
            // Token was not associated in the first place, token is likely burned
            return;
        }

        nft.setMapKey(toKey);
        nftAccounts.associate(toKey.copy(), nftId);
        getTokenIdToToken().replace(nftId, nft);
        watch.stop();
        NftLedgerStatistics.recordTransferTokenDuration(watch.getTime(TimeUnit.MICROSECONDS));
        NftLedgerStatistics.addTransferTokenOperation();
    }

    /**
     * Get the {@link FCOneToManyRelation} describing the tokens that each account owns.
     */
    public FCOneToManyRelation<MapKey, NftId> getNftAccounts() {
        return nftAccounts;
    }

    /**
     * Get the number of tokens held by a given account.
     *
     * @param key the account in question
     * @return the number of tokens held by the account
     */
    public int numberOfTokensByAccount(final MapKey key) {
        return this.nftAccounts.getCount(key);
    }

    /**
     * Get some of the tokens held by an account.
     *
     * @param key        the account in question
     * @param startIndex the index of the first token to return (inclusive)
     * @param endIndex   the end index (exclusive), tokens greater or equal to startIndex and less than endIndex are
     *                   returned
     * @return a list of tokens for the requested range
     */
    public List<NftId> getTokensByAccount(final MapKey key, final int startIndex, final int endIndex) {
        return this.nftAccounts.getList(key, startIndex, endIndex);
    }
}
