// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.hints.PreprocessingVote;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsKeyPublicationTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPreprocessingVoteTransactionBody;
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
class HintsSubmissionsTest {
    @Mock
    private Executor executor;

    @Mock
    private AppContext appContext;

    @Mock
    private AppContext.Gossip gossip;

    @Mock
    private NodeInfo selfNodeInfo;

    @Mock
    private HintsKeyAccessor keyAccessor;

    @Mock
    private HintsContext signingContext;

    private HintsSubmissions subject;

    @BeforeEach
    void setUp() {
        given(gossip.isAvailable()).willReturn(true);
        subject = new HintsSubmissions(executor, appContext, keyAccessor, signingContext);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExpectedBodyForKeyPublication() {
        final var hintsKey = Bytes.wrap("HK");

        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);

        subject.submitHintsKey(1, 2, hintsKey);

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
        assertTrue(body.hasHintsKeyPublication());
        final var expectedBody = new HintsKeyPublicationTransactionBody(1, 2, hintsKey);
        assertEquals(expectedBody, body.hintsKeyPublicationOrThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExpectedBodyForCongruentVote() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);

        subject.submitHintsVote(123L, 456L);

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
        assertTrue(body.hasHintsPreprocessingVote());
        final var expectedBody = HintsPreprocessingVoteTransactionBody.newBuilder()
                .constructionId(123L)
                .vote(PreprocessingVote.newBuilder().congruentNodeId(456L).build())
                .build();
        assertEquals(expectedBody, body.hintsPreprocessingVoteOrThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesExpectedBodyForExplicitVote() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);

        final var explicitKeys = new PreprocessedKeys(Bytes.wrap("AK"), Bytes.wrap("VK"));

        subject.submitHintsVote(123L, explicitKeys);

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
        assertTrue(body.hasHintsPreprocessingVote());
        final var expectedBody = HintsPreprocessingVoteTransactionBody.newBuilder()
                .constructionId(123L)
                .vote(PreprocessingVote.newBuilder()
                        .preprocessedKeys(explicitKeys)
                        .build())
                .build();
        assertEquals(expectedBody, body.hintsPreprocessingVoteOrThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesKeyAccessorForPartialSignature() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);
        final var msg = Bytes.wrap("M");
        final var sig = Bytes.wrap("S");
        given(signingContext.constructionIdOrThrow()).willReturn(123L);
        given(keyAccessor.signWithBlsPrivateKey(123L, msg)).willReturn(sig);

        subject.submitPartialSignature(msg);

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
        assertTrue(body.hasHintsPartialSignature());
        final var expectedBody = HintsPartialSignatureTransactionBody.newBuilder()
                .constructionId(123L)
                .message(msg)
                .partialSignature(sig)
                .build();
        assertEquals(expectedBody, body.hintsPartialSignatureOrThrow());
    }
}
