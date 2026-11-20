// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.fixtures.AppTestBase.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.hiero.base.crypto.Signature;
import org.hiero.base.crypto.SignatureType;
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

    private HintsSubmissions subject;

    @BeforeEach
    void setUp() {
        given(gossip.isAvailable()).willReturn(true);
        subject = new HintsSubmissions(executor, appContext, keyAccessor);
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

        subject.submitHintsKey(3, 4, 1, 4, hintsKey);

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
        final var expectedBody = HintsKeyPublicationTransactionBody.newBuilder()
                .constructionId(3)
                .crsId(4)
                .partyId(1)
                .numParties(4)
                .hintsKey(hintsKey)
                .build();
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

        final var explicitKeys = new PreprocessedKeys(Bytes.wrap(new byte[49]), Bytes.wrap("VK"));

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
        given(keyAccessor.signWithBlsPrivateKey(123L, msg)).willReturn(sig);
        given(gossip.submitFuture(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .willReturn(new CompletableFuture<>());

        subject.submitPartialSignature(123L, msg, () -> {});

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

    @Test
    @SuppressWarnings("unchecked")
    void runsOnSubmittedOnlyOnceThePartialSignatureIsSubmitted() {
        givenGossipAvailable();
        final var msg = Bytes.wrap("M");
        given(keyAccessor.signWithBlsPrivateKey(123L, msg)).willReturn(Bytes.wrap("S"));
        final var gossipFuture = new CompletableFuture<Void>();
        given(gossip.submitFuture(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .willReturn(gossipFuture);
        final var submitted = new AtomicBoolean();

        final var future = subject.submitPartialSignature(123L, msg, () -> submitted.set(true));

        final ArgumentCaptor<Consumer<TransactionBody.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(gossip).submitFuture(any(), any(), any(), captor.capture(), any(), anyInt(), anyInt(), any(), any());
        captor.getValue().accept(TransactionBody.newBuilder());
        assertFalse(submitted.get());
        gossipFuture.complete(null);
        assertTrue(submitted.get());
        assertSame(gossipFuture, future);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotRunOnSubmittedIfThePartialSignatureCannotBeSubmitted() {
        givenGossipAvailable();
        final var msg = Bytes.wrap("M");
        given(keyAccessor.signWithBlsPrivateKey(123L, msg)).willReturn(Bytes.wrap("S"));
        final var gossipFuture = new CompletableFuture<Void>();
        given(gossip.submitFuture(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .willReturn(gossipFuture);
        final var submitted = new AtomicBoolean();

        subject.submitPartialSignature(123L, msg, () -> submitted.set(true));

        final ArgumentCaptor<Consumer<TransactionBody.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(gossip).submitFuture(any(), any(), any(), captor.capture(), any(), anyInt(), anyInt(), any(), any());
        captor.getValue().accept(TransactionBody.newBuilder());
        gossipFuture.completeExceptionally(new IllegalStateException("Out of retries"));
        assertFalse(submitted.get());
    }

    @Test
    void doesNotRunOnSubmittedIfGossipIsUnavailable() {
        given(appContext.gossip()).willReturn(gossip);
        given(gossip.isAvailable()).willReturn(false);
        final var submitted = new AtomicBoolean();

        final var future = subject.submitPartialSignature(123L, Bytes.wrap("M"), () -> submitted.set(true));

        // The submission is skipped, but its future still completes normally
        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
        assertFalse(submitted.get());
        verifyNoInteractions(keyAccessor);
    }

    private void givenGossipAvailable() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesPlatformSignerForRsaSignature() {
        given(selfNodeInfo.accountId()).willReturn(AccountID.DEFAULT);
        given(appContext.selfNodeInfoSupplier()).willReturn(() -> selfNodeInfo);
        given(appContext.instantSource()).willReturn(() -> Instant.EPOCH);
        given(appContext.configSupplier()).willReturn(() -> DEFAULT_CONFIG);
        given(appContext.gossip()).willReturn(gossip);
        final var msg = Bytes.wrap("M");
        final var sig = Bytes.wrap("RSA");
        given(gossip.sign(any(byte[].class))).willReturn(new Signature(SignatureType.RSA, sig));

        subject.submitRsaSignature(msg);

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
        final ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(gossip).sign(bytesCaptor.capture());
        assertArrayEquals(msg.toByteArray(), bytesCaptor.getValue());
        final var body = builder.build();
        assertTrue(body.hasHintsPartialSignature());
        final var expectedBody = HintsPartialSignatureTransactionBody.newBuilder()
                .constructionId(RsaContext.CONSTRUCTION_ID)
                .message(msg)
                .partialSignature(sig)
                .build();
        assertEquals(expectedBody, body.hintsPartialSignatureOrThrow());
    }
}
