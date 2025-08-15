// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.history.HistoryProof;
import com.hedera.hapi.node.state.history.HistorySignature;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistorySubmissionsTest {
    @Mock
    private Executor executor;

    @Mock
    private AppContext appContext;

    @Mock
    private AppContext.Gossip gossip;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private Consumer<TransactionBody.Builder> spec;

    private HistorySubmissions subject;

    @BeforeEach
    void setUp() {
        given(gossip.isAvailable()).willReturn(true);
        subject = new HistorySubmissions(executor, appContext);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExpectedBodyForKeyPublication() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);

        subject.submitProofKeyPublication(Bytes.EMPTY);

        final ArgumentCaptor<Consumer<TransactionBody.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(gossip)
                .submitFuture(
                        eq(AccountID.DEFAULT),
                        eq(Instant.EPOCH),
                        any(),
                        captor.capture(),
                        any(),
                        anyInt(),
                        anyInt(),
                        any(),
                        any());
        final var spec = captor.getValue();
        final var builder = TransactionBody.newBuilder();
        spec.accept(builder);
        final var body = builder.build();
        assertTrue(body.hasHistoryProofKeyPublication());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExpectedBodyForVote() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);

        subject.submitProofVote(123L, HistoryProof.DEFAULT);

        final ArgumentCaptor<Consumer<TransactionBody.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(gossip)
                .submitFuture(
                        eq(AccountID.DEFAULT),
                        eq(Instant.EPOCH),
                        any(),
                        captor.capture(),
                        any(),
                        anyInt(),
                        anyInt(),
                        any(),
                        any());
        final var spec = captor.getValue();
        final var builder = TransactionBody.newBuilder();
        spec.accept(builder);
        final var body = builder.build();
        final var vote = body.historyProofVoteOrThrow();
        assertEquals(123L, vote.constructionId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExpectedBodyForSignature() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);

        subject.submitAssemblySignature(123L, HistorySignature.DEFAULT);

        final ArgumentCaptor<Consumer<TransactionBody.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(gossip)
                .submitFuture(
                        eq(AccountID.DEFAULT),
                        eq(Instant.EPOCH),
                        any(),
                        captor.capture(),
                        any(),
                        anyInt(),
                        anyInt(),
                        any(),
                        any());
        final var spec = captor.getValue();
        final var builder = TransactionBody.newBuilder();
        spec.accept(builder);
        final var body = builder.build();
        final var vote = body.historyProofSignatureOrThrow();
        assertEquals(123L, vote.constructionId());
    }
}
