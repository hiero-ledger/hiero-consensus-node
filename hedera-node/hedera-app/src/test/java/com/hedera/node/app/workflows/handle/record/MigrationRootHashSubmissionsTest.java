// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static java.time.temporal.ChronoUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.blockrecords.MigrationRootHashVoteTransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MigrationRootHashSubmissionsTest {
    @Mock
    private ExecutorService executor;

    @Mock
    private AppContext appContext;

    @Mock
    private AppContext.Gossip gossip;

    @Mock
    private NodeInfo selfNodeInfo;

    private MigrationRootHashSubmissions subject;

    @BeforeEach
    void setUp() {
        subject = new MigrationRootHashSubmissions(executor, appContext);
    }

    @Test
    void submitsStartupVoteAsExpectedWithActiveGossip() {
        final var op = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediatePreviousBlockRootHashes(List.of(Bytes.wrap(new byte[48])))
                .wrappedIntermediateBlockRootsLeafCount(1L)
                .build();
        final var now = Instant.EPOCH;
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> now);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);
        given(gossip.isAvailable()).willReturn(true);
        final var adminConfig = DEFAULT_CONFIG.getConfigData(NetworkAdminConfig.class);
        final var hederaConfig = DEFAULT_CONFIG.getConfigData(HederaConfig.class);

        final var submitted = subject.submitStartupVoteIfActive(op);

        assertTrue(submitted);
        final var specCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(gossip)
                .submitFuture(
                        eq(AccountID.DEFAULT),
                        eq(now),
                        eq(Duration.of(hederaConfig.transactionMaxValidDuration(), SECONDS)),
                        specCaptor.capture(),
                        eq(executor),
                        eq(adminConfig.timesToTrySubmission()),
                        eq(adminConfig.distinctTxnIdsToTry()),
                        eq(adminConfig.retryDelay()),
                        any());
        final var bodyBuilder = TransactionBody.newBuilder();
        @SuppressWarnings("unchecked")
        final var spec = (Consumer<TransactionBody.Builder>) specCaptor.getValue();
        spec.accept(bodyBuilder);
        final var body = bodyBuilder.build();
        assertEquals("Migration wrapped record root hash vote", body.memo());
        assertEquals(op, body.migrationRootHashVoteOrThrow());
    }

    @Test
    void submitsNothingIfGossipNotAvailable() {
        final var op = MigrationRootHashVoteTransactionBody.newBuilder()
                .previousWrappedRecordBlockRootHash(Bytes.wrap(new byte[48]))
                .wrappedIntermediateBlockRootsLeafCount(0L)
                .build();
        given(appContext.gossip()).willReturn(gossip);
        given(gossip.isAvailable()).willReturn(false);

        final var submitted = subject.submitStartupVoteIfActive(op);

        assertFalse(submitted);
        verify(gossip, never())
                .submitFuture(any(), any(), any(), any(), any(), any(Integer.class), any(Integer.class), any(), any());
    }

    @Test
    void submitStartupVoteRequiresNonNullVote() {
        assertThrows(NullPointerException.class, () -> subject.submitStartupVoteIfActive(null));
    }
}
