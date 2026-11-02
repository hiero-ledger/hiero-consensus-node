// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.amendments;

import static com.hedera.hapi.block.stream.output.StateIdentifier.STATE_ID_ACCOUNTS;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.mapKeyFor;
import static com.hedera.node.app.hapi.utils.blocks.BlockStreamUtils.mapValueFor;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.amendedItems;
import static com.hedera.services.bdd.junit.support.amendments.BlockAmendmentReplayer.stateChangesInReplayOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.hedera.hapi.block.stream.BlockItem;
import com.hedera.hapi.block.stream.output.BlockFooter;
import com.hedera.hapi.block.stream.output.BlockHeader;
import com.hedera.hapi.block.stream.output.MapChangeKey;
import com.hedera.hapi.block.stream.output.MapChangeValue;
import com.hedera.hapi.block.stream.output.MapDeleteChange;
import com.hedera.hapi.block.stream.output.MapUpdateChange;
import com.hedera.hapi.block.stream.output.StateChange;
import com.hedera.hapi.block.stream.output.StateChanges;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.network.BlockAmendment;
import com.hedera.hapi.node.network.BlockAmendmentOperation;
import com.hedera.hapi.node.network.InsertStateChanges;
import com.hedera.hapi.node.state.token.Account;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.merkledb.MerkleDbDataSourceBuilder;
import com.swirlds.state.lifecycle.StateDefinition;
import com.swirlds.state.lifecycle.StateMetadata;
import com.swirlds.state.merkle.VirtualMapStateImpl;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.virtualmap.VirtualMap;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Demonstrates, with a real {@link VirtualMap}, why a state amendment must place each state change at an exact
 * position: a {@code VirtualMap} assigns leaf paths by the order of insertions and removals, so the same changes
 * applied in a different order produce a different root hash. An amendment that re-inserts a dropped change at its
 * original position reproduces the original root hash exactly; the same change at another position does not.
 */
class AmendedStateReplayHashTest {
    private static final String SERVICE_NAME = "TokenService";
    private static final int ACCOUNTS_STATE_ID = STATE_ID_ACCOUNTS.protoOrdinal();
    private static final Timestamp NOW = new Timestamp(1_234_567L, 0);
    private static final long BLOCK_NUMBER = 42;
    private static final Configuration CONFIGURATION = ConfigurationBuilder.create()
            .autoDiscoverExtensions()
            .withValue("merkleDb.initialCapacity", "" + 65_536L)
            .build();

    @TempDir
    private Path tempDir;

    /**
     * Prior blocks created accounts 1-5. "Block N" then adds account 6, removes account 2, adds account 7, and
     * updates account 3. The amendment re-inserts the dropped "add account 6".
     */
    private static final List<StateChange> PRIOR = List.of(put(1, 10), put(2, 20), put(3, 30), put(4, 40), put(5, 50));

    private static final List<StateChange> BLOCK_N = List.of(put(6, 60), delete(2), put(7, 70), put(3, 31));

    @Test
    void positionOfAnAddedLeafChangesTheRootHash() {
        final var inOrder = replayHash(concat(PRIOR, BLOCK_N));
        final var reordered = replayHash(concat(PRIOR, List.of(delete(2), put(6, 60), put(7, 70), put(3, 31))));

        assertNotEquals(inOrder, reordered, "The same changes in a different order should hash differently");
    }

    @Test
    void amendmentAtTheOriginalPositionReproducesTheSignedRootHash() {
        // The root hash the next block would sign, from the correct sequence of changes
        final var signedRootHash = replayHash(concat(PRIOR, BLOCK_N));

        // The block as emitted by a consensus node that dropped the first change
        final var emitted = block(BLOCK_N.subList(1, BLOCK_N.size()));
        assertNotEquals(signedRootHash, replayHash(concat(PRIOR, stateChangesInReplayOrder(emitted))));

        // The amendment re-inserts the dropped change before what is now the emitted block's first change
        final var amended = amendedItems(emitted, List.of(insertAt(0, put(6, 60))));
        assertEquals(signedRootHash, replayHash(concat(PRIOR, stateChangesInReplayOrder(amended))));

        // The same change at any other position does not reproduce the signed root hash
        for (int position = 1; position <= BLOCK_N.size() - 1; position++) {
            final var misplaced = amendedItems(emitted, List.of(insertAt(position, put(6, 60))));
            assertNotEquals(
                    signedRootHash,
                    replayHash(concat(PRIOR, stateChangesInReplayOrder(misplaced))),
                    "Inserting at position " + position + " should not reproduce the signed root hash");
        }
    }

    /**
     * Applies each state change individually and in order to a fresh {@link VirtualMapStateImpl}, then returns the
     * root hash.
     */
    private Hash replayHash(final List<StateChange> changes) {
        final var fileSystemManager = new FileSystemManager(tempDir.resolve("replay-" + System.nanoTime()));
        final var dataSourceBuilder = new MerkleDbDataSourceBuilder(CONFIGURATION, fileSystemManager, 1_000L);
        final var virtualMap = new VirtualMap(dataSourceBuilder, CONFIGURATION);
        final var state = new VirtualMapStateImpl(virtualMap, new NoOpMetrics());
        state.initializeState(new StateMetadata<>(
                SERVICE_NAME,
                StateDefinition.keyValue(ACCOUNTS_STATE_ID, "ACCOUNTS", AccountID.PROTOBUF, Account.PROTOBUF)));
        for (final var change : changes) {
            final var writableStates = state.getWritableStates(SERVICE_NAME);
            final var accounts = writableStates.<Object, Object>get(change.stateId());
            switch (change.changeOperation().kind()) {
                case MAP_UPDATE ->
                    accounts.put(
                            mapKeyFor(change.mapUpdateOrThrow().keyOrThrow()),
                            mapValueFor(change.mapUpdateOrThrow().valueOrThrow()));
                case MAP_DELETE ->
                    accounts.remove(mapKeyFor(change.mapDeleteOrThrow().keyOrThrow()));
                default -> throw new IllegalArgumentException("Unexpected change " + change);
            }
            ((CommittableWritableStates) writableStates).commit();
        }
        final var root = state.getRoot();
        final var next = root.copy();
        try {
            return root.getHash();
        } finally {
            root.release();
            next.release();
        }
    }

    private static List<BlockItem> block(final List<StateChange> changes) {
        return List.of(
                BlockItem.newBuilder()
                        .blockHeader(
                                BlockHeader.newBuilder().number(BLOCK_NUMBER).build())
                        .build(),
                BlockItem.newBuilder()
                        .stateChanges(new StateChanges(NOW, changes))
                        .build(),
                BlockItem.newBuilder().blockFooter(BlockFooter.DEFAULT).build());
    }

    private static BlockAmendment insertAt(final int beforeStateChangeIndex, final StateChange change) {
        return BlockAmendment.newBuilder()
                .blockNumber(BLOCK_NUMBER)
                .operations(BlockAmendmentOperation.newBuilder()
                        .insertStateChanges(InsertStateChanges.newBuilder()
                                .itemIndex(1)
                                .beforeStateChangeIndex(beforeStateChangeIndex)
                                .stateChanges(List.of(StateChange.PROTOBUF.toBytes(change)))
                                .build())
                        .build())
                .build();
    }

    private static StateChange put(final long accountNum, final long balance) {
        final var id = AccountID.newBuilder().accountNum(accountNum).build();
        return StateChange.newBuilder()
                .stateId(ACCOUNTS_STATE_ID)
                .mapUpdate(MapUpdateChange.newBuilder()
                        .key(MapChangeKey.newBuilder().accountIdKey(id).build())
                        .value(MapChangeValue.newBuilder()
                                .accountValue(Account.newBuilder()
                                        .accountId(id)
                                        .tinybarBalance(balance)
                                        .build())
                                .build())
                        .build())
                .build();
    }

    private static StateChange delete(final long accountNum) {
        return StateChange.newBuilder()
                .stateId(ACCOUNTS_STATE_ID)
                .mapDelete(new MapDeleteChange(MapChangeKey.newBuilder()
                        .accountIdKey(AccountID.newBuilder().accountNum(accountNum))
                        .build()))
                .build();
    }

    private static List<StateChange> concat(final List<StateChange> a, final List<StateChange> b) {
        final List<StateChange> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
