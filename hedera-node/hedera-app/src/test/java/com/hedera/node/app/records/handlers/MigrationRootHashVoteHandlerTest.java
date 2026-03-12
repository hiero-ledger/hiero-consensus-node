// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.blockrecords.MigrationRootHashVoteTally;
import com.hedera.hapi.node.state.blockrecords.MigrationWrappedHashes;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.records.WritableMigrationRootHashStore;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
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
    private WritableMigrationRootHashStore store;

    @Mock
    private ReadableRosterStore rosterStore;

    @Mock
    private NodeInfo nodeInfo;

    private MigrationRootHashVoteHandler subject;

    @BeforeEach
    void setUp() {
        subject = new MigrationRootHashVoteHandler();
    }

    @Test
    void pureChecksAndPreHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void handleIsNoopWhenVotingAlreadyComplete() {
        given(context.storeFactory()).willReturn(storeFactory);
        given(storeFactory.writableStore(WritableMigrationRootHashStore.class)).willReturn(store);
        given(store.isVotingComplete()).willReturn(true);

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store, never()).putVoteIfAbsent(anyLong(), any());
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
        given(storeFactory.writableStore(WritableMigrationRootHashStore.class)).willReturn(store);
        given(storeFactory.readableStore(ReadableRosterStore.class)).willReturn(rosterStore);
        given(store.isVotingComplete()).willReturn(false);
        given(store.putVoteIfAbsent(NODE_ID, vote)).willReturn(true);
        given(rosterStore.getActiveRoster()).willReturn(activeRoster);
        given(store.getTally(any()))
                .willReturn(MigrationRootHashVoteTally.newBuilder()
                        .totalWeight(11L)
                        .voteCount(1L)
                        .build());
        given(store.queuedHashesInOrder()).willReturn(List.of(queuedHashes));

        assertDoesNotThrow(() -> subject.handle(context));

        verify(store).addToTally(any(), anyLong());
        verify(store).applyFinalizedValuesAndMarkComplete(any(), any(), any(), anyLong());
    }
}
