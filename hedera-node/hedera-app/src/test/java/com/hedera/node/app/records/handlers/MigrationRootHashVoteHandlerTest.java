// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.node.state.blockrecords.NodeMigrationRootHashVote;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.platform.state.NodeId;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.WritableBlockRecordStore;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationRootHashVoteHandlerTest {
    private static final long NODE_ID = 0L;

    @Mock
    private HandleContext context;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private WritableBlockRecordStore store;

    @Mock
    private ReadableRosterStore rosterStore;

    @Mock
    private NodeInfo nodeInfo;

    @Mock
    private BlockRecordManager blockRecordManager;

    private MigrationRootHashVoteHandler subject;

    @BeforeEach
    void setUp() {
        subject = new MigrationRootHashVoteHandler(blockRecordManager);
    }

    @Test
    void preHandleDoesNothing() {
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void pureChecksAcceptsWellFormedVote() {
        // 48-byte previous hash; leafCount 0 -> bitCount(0) == 0 -> empty intermediate list
        lenient()
                .when(pureChecksContext.body())
                .thenReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                        .wrappedIntermediateBlockRootsLeafCount(0)
                        .build()));

        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void pureChecksRejectsWrongLengthPreviousHash() {
        given(pureChecksContext.body())
                .willReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[47])) // not SHA-384 (48)
                        .wrappedIntermediateBlockRootsLeafCount(0)
                        .build()));

        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void pureChecksRejectsWrongLengthIntermediateHash() {
        // leafCount 1 -> bitCount(1) == 1 -> exactly one intermediate hash expected, but it is the wrong length
        given(pureChecksContext.body())
                .willReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                        .wrappedIntermediatePreviousBlockRootHashes(List.of(Bytes.wrap(new byte[47])))
                        .wrappedIntermediateBlockRootsLeafCount(1)
                        .build()));

        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void pureChecksRejectsIntermediateListSizeMismatchedWithLeafCount() {
        // leafCount 0 -> bitCount(0) == 0 expected, but one element is supplied -> structural mismatch
        given(pureChecksContext.body())
                .willReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                        .wrappedIntermediatePreviousBlockRootHashes(List.of(Bytes.wrap(new byte[48])))
                        .wrappedIntermediateBlockRootsLeafCount(0)
                        .build()));

        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void pureChecksRejectsOutOfRangeLeafCount() {
        // Long.MIN_VALUE has bitCount 1, so the (size == bitCount) check still passes with one element,
        // but the leaf count itself is nonsensical (negative as signed / astronomically large as unsigned)
        given(pureChecksContext.body())
                .willReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                        .wrappedIntermediatePreviousBlockRootHashes(List.of(Bytes.wrap(new byte[48])))
                        .wrappedIntermediateBlockRootsLeafCount(Long.MIN_VALUE)
                        .build()));

        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void pureChecksRejectsEmptyPreviousHash() {
        // an absent/empty previous hash is not a valid SHA-384 (48-byte) digest
        given(pureChecksContext.body())
                .willReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.EMPTY)
                        .wrappedIntermediateBlockRootsLeafCount(0)
                        .build()));

        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void pureChecksRejectsOversizedIntermediateList() {
        // leafCount 1 -> bitCount(1) == 1 expected, but two elements are supplied -> structural mismatch
        given(pureChecksContext.body())
                .willReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                        .wrappedIntermediatePreviousBlockRootHashes(
                                List.of(Bytes.wrap(new byte[48]), Bytes.wrap(new byte[48])))
                        .wrappedIntermediateBlockRootsLeafCount(1)
                        .build()));

        assertThrows(PreCheckException.class, () -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void pureChecksAcceptsValidNonZeroLeafCount() {
        // leafCount 3 (binary 11) -> bitCount == 2 -> exactly two 48-byte intermediate hashes
        lenient()
                .when(pureChecksContext.body())
                .thenReturn(bodyFor(MigrationRootHashVoteTransactionBody.newBuilder()
                        .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                        .wrappedIntermediatePreviousBlockRootHashes(
                                List.of(Bytes.wrap(new byte[48]), Bytes.wrap(new byte[48])))
                        .wrappedIntermediateBlockRootsLeafCount(3)
                        .build()));

        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void handleIsNoopWhenVotingAlreadyComplete() {
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(store.isVotingComplete()).willReturn(true);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).putVoteIfAbsent(anyLong(), any(), anyInt());
    }

    @Test
    void handleFinalizesWhenTallyExceedsOneThirdThreshold() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();
        final var queuedHashes = MigrationWrappedHashes.newBuilder()
                .blockNumber(1L)
                .consensusTimestampHash(Bytes.wrap(new byte[48]))
                .outputItemsTreeRootHash(Bytes.wrap(new byte[48]))
                .build();
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(20L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(10L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(store.putVoteIfAbsent(eq(NODE_ID), eq(vote), anyInt())).willReturn(true);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        given(store.votes())
                .willReturn(List.of(NodeMigrationRootHashVote.newBuilder()
                        .nodeId(new NodeId(NODE_ID))
                        .vote(vote)
                        .build()));
        given(store.wrappedHashesInOrder()).willReturn(List.of(queuedHashes));

        subject.handle(context);

        verify(store).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleIsNoopForDuplicateVote() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        // an eligible submitter, so the flow reaches the duplicate check before returning
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(1L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(1L).build()));
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        given(store.putVoteIfAbsent(eq(NODE_ID), eq(vote), anyInt())).willReturn(false);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleDoesntFinalizeVoteWhenThresholdNotReached() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(10L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(20L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(store.putVoteIfAbsent(eq(NODE_ID), eq(vote), anyInt())).willReturn(true);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        given(store.votes())
                .willReturn(List.of(NodeMigrationRootHashVote.newBuilder()
                        .nodeId(new NodeId(NODE_ID))
                        .vote(vote)
                        .build()));

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleIsNoopWhenNoActiveRoster() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(rosterStore.getActiveRoster()).willReturn(null);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleIsNoopWhenActiveRosterHasNoEntries() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();
        final var emptyActiveRoster = new Roster(List.of());

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(rosterStore.getActiveRoster()).willReturn(emptyActiveRoster);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).votes();
        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleIsNoopWhenNodeWeightIsNonPositive() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(0L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(1L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).votes();
        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleIsNoopWhenTotalWeightIsNonPositive() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();
        final var activeRoster = new Roster(
                List.of(RosterEntry.newBuilder().nodeId(NODE_ID).weight(-1L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).votes();
        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleTalliesEquivalentVoteBodiesFromDifferentInstances() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var equivalentVote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body =
                TransactionBody.newBuilder().migrationRootHashVote(vote).build();
        final var queuedHashes = MigrationWrappedHashes.newBuilder()
                .blockNumber(1L)
                .consensusTimestampHash(Bytes.wrap(new byte[48]))
                .outputItemsTreeRootHash(Bytes.wrap(new byte[48]))
                .build();
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(1L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(1L).build(),
                RosterEntry.newBuilder().nodeId(2L).weight(1L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(store.putVoteIfAbsent(eq(NODE_ID), eq(vote), anyInt())).willReturn(true);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        given(store.votes())
                .willReturn(List.of(
                        NodeMigrationRootHashVote.newBuilder()
                                .nodeId(new NodeId(NODE_ID))
                                .vote(vote)
                                .build(),
                        NodeMigrationRootHashVote.newBuilder()
                                .nodeId(new NodeId(1L))
                                .vote(equivalentVote)
                                .build()));
        given(store.wrappedHashesInOrder()).willReturn(List.of(queuedHashes));

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleDoesNotCreditBodilessStoredVoteToTally() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body = bodyFor(vote);
        final var queuedHashes = MigrationWrappedHashes.newBuilder()
                .blockNumber(1L)
                .consensusTimestampHash(Bytes.wrap(new byte[48]))
                .outputItemsTreeRootHash(Bytes.wrap(new byte[48]))
                .build();
        // submitter (node 0) + two other weight-1 nodes, total 3; threshold is tally*3 > 3, i.e. tally >= 2
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(1L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(1L).build(),
                RosterEntry.newBuilder().nodeId(2L).weight(1L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(store.putVoteIfAbsent(eq(NODE_ID), eq(vote), anyInt())).willReturn(true);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        // node 1's stored vote carries NO body; its weight must not be credited to the submitter's op
        given(store.votes())
                .willReturn(List.of(
                        NodeMigrationRootHashVote.newBuilder()
                                .nodeId(new NodeId(NODE_ID))
                                .vote(vote)
                                .build(),
                        NodeMigrationRootHashVote.newBuilder()
                                .nodeId(new NodeId(1L))
                                .build()));
        lenient().when(store.wrappedHashesInOrder()).thenReturn(List.of(queuedHashes));

        assertDoesNotThrow(() -> subject.handle(context));

        // Only the submitter's own weight (1) backs the op -> below 1/3 -> must not finalize
        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleDoesNotCreditNodeIdlessStoredVoteToTally() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body = bodyFor(vote);
        final var queuedHashes = MigrationWrappedHashes.newBuilder()
                .blockNumber(1L)
                .consensusTimestampHash(Bytes.wrap(new byte[48]))
                .outputItemsTreeRootHash(Bytes.wrap(new byte[48]))
                .build();
        // submitter (node 0) + two other weight-1 nodes, total 3; threshold is tally*3 > 3, i.e. tally >= 2
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(1L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(1L).build(),
                RosterEntry.newBuilder().nodeId(2L).weight(1L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(store.putVoteIfAbsent(eq(NODE_ID), eq(vote), anyInt())).willReturn(true);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        // a stored entry that carries a vote but NO node id; without the guard it defaults to
        // NodeId.DEFAULT (id 0) and is miscredited with node 0's weight, doubling the submitter's tally
        given(store.votes())
                .willReturn(List.of(
                        NodeMigrationRootHashVote.newBuilder()
                                .nodeId(new NodeId(NODE_ID))
                                .vote(vote)
                                .build(),
                        NodeMigrationRootHashVote.newBuilder().vote(vote).build()));
        lenient().when(store.wrappedHashesInOrder()).thenReturn(List.of(queuedHashes));

        assertDoesNotThrow(() -> subject.handle(context));

        // the node-id-less entry must not alias to node 0 -> only the submitter's own weight (1) backs
        // the op -> below the 1/3 threshold -> must not finalize
        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    @Test
    void handleDoesNotStoreVoteFromZeroWeightNode() {
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0)
                .build();
        final var body = bodyFor(vote);
        // the submitting node has zero weight in the active roster -> ineligible to vote
        final var activeRoster = new Roster(List.of(
                RosterEntry.newBuilder().nodeId(NODE_ID).weight(0L).build(),
                RosterEntry.newBuilder().nodeId(1L).weight(1L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(store.isVotingComplete()).willReturn(false);
        lenient().when(storeFactory.readableStore(ReadableRosterStore.class)).thenReturn(rosterStore);
        lenient().when(rosterStore.getActiveRoster()).thenReturn(activeRoster);

        assertDoesNotThrow(() -> subject.handle(context));

        // an ineligible (zero-weight) node's vote must not be persisted
        verify(store, never()).putVoteIfAbsent(anyLong(), any(), anyInt());
    }

    @Test
    void handleDoesNotHaltOnStructurallyInconsistentVote() {
        // Defense-in-depth check on the consensus handle thread.
        //
        // The streaming hasher reconstructs its pending-subtree state from (intermediateHashes, leafCount)
        // and assumes the number of pending hashes equals Long.bitCount(leafCount). Here leafCount is 1
        // (one pending subtree expected) but the intermediate-hash list is empty, so the very first fold-up
        // during finalization pops from an empty list and raises an unchecked exception.
        //
        // Such a body is normally rejected upstream during semantic checks, so it should never reach handle()
        // on the common path. But handle() reconstructs the tally and the hasher from durable state rather
        // than from the freshly-checked body, so it must not assume the inputs are well-formed: a malformed
        // vote that does reach finalization must be ignored gracefully, never crash the handle thread, and
        // never be written into canonical state.
        final var vote = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(1)
                .build();
        final var body = bodyFor(vote);
        final var queuedHashes = MigrationWrappedHashes.newBuilder()
                .blockNumber(1L)
                .consensusTimestampHash(Bytes.wrap(new byte[48]))
                .outputItemsTreeRootHash(Bytes.wrap(new byte[48]))
                .build();
        // single-node roster, so the lone vote already exceeds the 1/3 threshold and finalization is attempted
        final var activeRoster = new Roster(
                List.of(RosterEntry.newBuilder().nodeId(NODE_ID).weight(1L).build()));

        given(context.storeFactory()).willReturn(storeFactory);
        given(context.body()).willReturn(body);
        given(context.creatorInfo()).willReturn(nodeInfo);
        given(nodeInfo.nodeId()).willReturn(NODE_ID);
        given(storeFactory.writableStore(WritableBlockRecordStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(store.putVoteIfAbsent(eq(NODE_ID), eq(vote), anyInt())).willReturn(true);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        given(store.votes())
                .willReturn(List.of(NodeMigrationRootHashVote.newBuilder()
                        .nodeId(new NodeId(NODE_ID))
                        .vote(vote)
                        .build()));
        lenient().when(store.wrappedHashesInOrder()).thenReturn(List.of(queuedHashes));

        // a malformed vote must not crash the handle thread...
        assertDoesNotThrow(() -> subject.handle(context));
        // ...and must not be finalized into canonical state
        verify(store, never()).applyFinalizedValuesAndMarkComplete(any(), any(), anyLong());
    }

    private static TransactionBody bodyFor(final MigrationRootHashVoteTransactionBody vote) {
        return TransactionBody.newBuilder().migrationRootHashVote(vote).build();
    }
}
