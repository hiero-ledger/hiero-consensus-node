// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.base.utility.Pair;
import com.swirlds.common.test.fixtures.merkle.util.MerkleTestUtils;
import com.swirlds.demo.platform.nft.Nft;
import com.swirlds.demo.platform.nft.NftId;
import com.swirlds.demo.platform.nft.NftLedger;
import com.swirlds.demo.platform.nft.ReferenceNftLedger;
import com.swirlds.merkle.test.fixtures.map.pta.MapKey;
import com.swirlds.merkle.tree.MerkleTreeInternalNode;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import org.hiero.base.constructable.ClassConstructorPair;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("NFT Ledger Tests")
class NftLedgerTests {

    @BeforeAll
    public static void startUp() throws ConstructableRegistryException {
        final ConstructableRegistry registry = ConstructableRegistry.getInstance();
        registry.registerConstructable(new ClassConstructorPair(Nft.class, Nft::new));
        registry.registerConstructable(new ClassConstructorPair(NftId.class, NftId::new));
        registry.registerConstructable(new ClassConstructorPair(MapKey.class, MapKey::new));
        registry.registerConstructable(
                new ClassConstructorPair(MerkleTreeInternalNode.class, MerkleTreeInternalNode::new));
    }

    /**
     * Create a new list containing a ledger and a reference ledger.
     */
    private List<Pair<ReferenceNftLedger, NftLedger>> createNewLedgerList(final double tokenTrackingFraction) {
        final List<Pair<ReferenceNftLedger, NftLedger>> list = new LinkedList<>();
        list.add(Pair.of(new ReferenceNftLedger(tokenTrackingFraction), new NftLedger()));
        return list;
    }

    /**
     * Build a {@link MapKey}
     */
    private MapKey mapKey(final long id) {
        return new MapKey(0, 0, id);
    }

    /**
     * Build an {@link NftId}
     */
    private NftId nftId(final long id) {
        return new NftId(0, 0, id);
    }

    private Nft nft(final long tokenId, final long ownerId, final String tokenName, final String tokenMemo) {
        final Nft nft = new Nft();

        nft.setTokenNum(tokenId);

        nft.setMapKey(mapKey(ownerId));
        nft.setMemo(tokenMemo);
        nft.setSerialNumber(tokenName);

        return nft;
    }

    /**
     * Make a copy and add it to the end of the list.
     */
    private void makeCopy(final List<Pair<ReferenceNftLedger, NftLedger>> copies) {
        assertFalse(copies.isEmpty(), "there must be at least one copy");
        final Pair<ReferenceNftLedger, NftLedger> latest = copies.get(copies.size() - 1);
        copies.add(Pair.of(latest.left().copy(), latest.right().copy()));
    }

    /**
     * Release one of the copies.
     */
    private void releaseCopy(final List<Pair<ReferenceNftLedger, NftLedger>> copies, final int indexToDelete) {

        assertTrue(copies.size() > indexToDelete, "index must be within bounds");

        final Pair<ReferenceNftLedger, NftLedger> pair = copies.get(indexToDelete);
        pair.right().release();

        copies.remove(indexToDelete);
    }

    /**
     * Mint a token.
     */
    private void mint(
            final Random random,
            final List<Pair<ReferenceNftLedger, NftLedger>> copies,
            final long tokenId,
            final long ownerId) {

        final Pair<ReferenceNftLedger, NftLedger> pair = copies.get(copies.size() - 1);
        final ReferenceNftLedger referenceLedger = pair.left();
        final NftLedger ledger = pair.right();

        if (referenceLedger.getTokenMap().containsKey(nftId(tokenId))) {
            // NftLedger doesn't like having tokens with the same ID minted more than once
            return;
        }

        final byte[] tokenNameBytes = new byte[5];
        random.nextBytes(tokenNameBytes);
        final String tokenName = new String(tokenNameBytes);

        final byte[] tokenMemoBytes = new byte[10];
        random.nextBytes(tokenMemoBytes);
        final String tokenMemo = new String(tokenMemoBytes);

        referenceLedger.mintToken(mapKey(ownerId), nftId(tokenId), nft(tokenId, ownerId, tokenName, tokenMemo));
        ledger.mintToken(mapKey(ownerId), nftId(tokenId), nft(tokenId, ownerId, tokenName, tokenMemo));
    }

    /**
     * Burn a token.
     */
    private void burn(final List<Pair<ReferenceNftLedger, NftLedger>> copies, final long tokenId) {

        final Pair<ReferenceNftLedger, NftLedger> pair = copies.get(copies.size() - 1);
        final ReferenceNftLedger referenceLedger = pair.left();
        final NftLedger ledger = pair.right();

        referenceLedger.burnToken(nftId(tokenId));
        ledger.burnToken(nftId(tokenId));
    }

    /**
     * Transfer a token.
     */
    private void transfer(
            final List<Pair<ReferenceNftLedger, NftLedger>> copies, final long tokenId, final long newOwnerId) {

        final Pair<ReferenceNftLedger, NftLedger> pair = copies.get(copies.size() - 1);
        final ReferenceNftLedger referenceLedger = pair.left();
        final NftLedger ledger = pair.right();

        referenceLedger.transferToken(nftId(tokenId), mapKey(newOwnerId));
        ledger.transferToken(nftId(tokenId), mapKey(newOwnerId));
    }

    /**
     * Validate that the data in the reference ledger matches the data in the FC ledger.
     */
    private void validate(final Pair<ReferenceNftLedger, NftLedger> copy) {
        final ReferenceNftLedger referenceLedger = copy.left();
        final NftLedger ledger = copy.right();

        // Validate the token map
        assertEquals(
                referenceLedger.getTokenMap().size(),
                ledger.getTokenIdToToken().size(),
                "number of tokens should match");
        for (final NftId tokenId : ledger.getTokenIdToToken().keySet()) {
            assertTrue(referenceLedger.getTokenMap().containsKey(tokenId), "map should contain the same token");
            assertEquals(
                    referenceLedger.getTokenMap().get(tokenId),
                    ledger.getTokenIdToToken().get(tokenId),
                    "tokens should match");
        }

        // Validate ownership info
        assertEquals(
                ledger.getNftAccounts().getKeyCount(),
                referenceLedger.getOwnershipMap().size(),
                "number of token owners should match");
        for (final MapKey ownerId : ledger.getNftAccounts().getKeySet()) {
            assertEquals(
                    referenceLedger.getOwnershipMap().get(ownerId),
                    new HashSet<>(ledger.getNftAccounts().getList(ownerId)),
                    "tokens owned should match");
        }
    }

    /**
     * Validate that the data in the reference ledger matches the data in the FC ledger for all copies.
     */
    private void validate(final List<Pair<ReferenceNftLedger, NftLedger>> copies) {
        for (final Pair<ReferenceNftLedger, NftLedger> copy : copies) {
            if (copy.left().getFractionToTrack() == 1) {
                // There is a lot of overlap between this validate and the token validation,
                // but it is sometimes nice to have different implementations of validation
                // to reduce the probability of programmer mistakes.
                validate(copy);
            }
            copy.left().assertValidity(copy.right());
        }

        if (!copies.isEmpty()) {
            final Pair<ReferenceNftLedger, NftLedger> mutablePair = copies.get(copies.size() - 1);
            final NftLedger ledger = mutablePair.right();

            // Make sure data structure contains mutable data
            assertTrue(MerkleTestUtils.isTreeMutable(ledger), "all nodes should be mutable");
        }
    }

    @ParameterizedTest
    @ValueSource(doubles = {1.0, 0.99, 0.75, 0.5, 0.25, 0.01, 0.0})
    @Tag(TestComponentTags.TESTING)
    @DisplayName("Randomized Test")
    void randomizedTest(final double tokenTrackingFraction) {

        final Random random = new Random();

        final int iterations = 10_000;
        final int maxOwnerId = 1000;
        final int maxTokenId = 1000;
        final int maxCopies = 5;

        final List<Pair<ReferenceNftLedger, NftLedger>> copies = createNewLedgerList(tokenTrackingFraction);

        for (int i = 0; i < iterations; i++) {

            final int transferTokenId = random.nextInt(maxTokenId);
            final int transferOwnerId = random.nextInt(maxOwnerId);
            transfer(copies, transferTokenId, transferOwnerId);

            // Every 5 iterations create and burn a token
            if (i % 5 == 0) {

                final int mintTokenId = random.nextInt(maxTokenId);
                final int mintOwnerId = random.nextInt(maxOwnerId);
                mint(random, copies, mintTokenId, mintOwnerId);

                final int burnTokenId = random.nextInt(maxTokenId);
                burn(copies, burnTokenId);
            }

            // Every 100 iterations make a copy and validate
            if (i % 100 == 0) {
                makeCopy(copies);

                // Don't let too many copies accumulate
                if (copies.size() > maxCopies) {
                    releaseCopy(copies, random.nextInt(copies.size() - 1));
                }

                validate(copies);
            }

            // Ensure that changing the tracking fraction works. Once per test modify it slightly.
            if (i > 0 && i % (iterations / 2) == 0 && tokenTrackingFraction > 0) {
                final Pair<ReferenceNftLedger, NftLedger> pair = copies.get(copies.size() - 1);
                pair.left().setFractionToTrack(pair.right(), tokenTrackingFraction - 0.01);
            }
        }
        validate(copies);

        // delete remaining copies
        while (!copies.isEmpty()) {
            releaseCopy(copies, 0);
            validate(copies);
        }
    }
}
