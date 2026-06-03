// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsContextTest {
    private static final byte[] VALID_AGGREGATION_KEY_BYTES = new byte[49];
    private static final Bytes BLOCK_HASH = Bytes.wrap("BH");
    private static final Bytes VERIFICATION_KEY = Bytes.wrap("VK");
    private static final Bytes AGGREGATION_KEY = Bytes.wrap(VALID_AGGREGATION_KEY_BYTES);
    private static final PreprocessedKeys PREPROCESSED_KEYS = new PreprocessedKeys(AGGREGATION_KEY, VERIFICATION_KEY);
    private static final byte[] NEXT_AGGREGATION_KEY_BYTES = new byte[49];
    private static final Bytes NEXT_VERIFICATION_KEY = Bytes.wrap("VK2");
    private static final Bytes NEXT_AGGREGATION_KEY;
    private static final PreprocessedKeys NEXT_PREPROCESSED_KEYS;
    private static final NodePartyId A_NODE_PARTY_ID = new NodePartyId(1L, 2, 1L);
    private static final NodePartyId B_NODE_PARTY_ID = new NodePartyId(3L, 6, 1L);
    private static final NodePartyId C_NODE_PARTY_ID = new NodePartyId(7L, 14, 1L);
    private static final NodePartyId D_NODE_PARTY_ID = new NodePartyId(9L, 18, 9L);
    private static final HintsConstruction CONSTRUCTION = HintsConstruction.newBuilder()
            .constructionId(1L)
            .hintsScheme(new HintsScheme(
                    PREPROCESSED_KEYS, List.of(A_NODE_PARTY_ID, B_NODE_PARTY_ID, C_NODE_PARTY_ID, D_NODE_PARTY_ID)))
            .build();
    private static final Bytes CRS = Bytes.wrap("CRS");
    private static final Bytes MESSAGE = Bytes.wrap("MESSAGE");
    private static final Bytes PARTIAL_SIGNATURE = Bytes.wrap("PARTIAL_SIGNATURE");

    static {
        NEXT_AGGREGATION_KEY_BYTES[0] = 1;
        NEXT_AGGREGATION_KEY = Bytes.wrap(NEXT_AGGREGATION_KEY_BYTES);
        NEXT_PREPROCESSED_KEYS = new PreprocessedKeys(NEXT_AGGREGATION_KEY, NEXT_VERIFICATION_KEY);
    }

    @Mock
    private HintsLibrary library;

    @Mock
    private Bytes signature;

    @Mock
    private Supplier<Configuration> configProvider;

    @Mock
    private Configuration configuration;

    @Mock
    private HintsSigningMetrics signingMetrics;

    private HintsContext subject;

    @BeforeEach
    void setUp() {
        lenient().when(configProvider.get()).thenReturn(configuration);
        lenient().when(configuration.getConfigData(TssConfig.class)).thenReturn(defaultConfig());
        subject = new HintsContext(library, configProvider, signingMetrics);
    }

    private static TssConfig defaultConfig() {
        return configWithValidateBlockSignatures(false);
    }

    private static TssConfig configWithValidateBlockSignatures(final boolean validateBlockSignatures) {
        return new TssConfig(
                Duration.ofSeconds(60),
                Duration.ofSeconds(300),
                Duration.ofSeconds(10),
                Duration.ofSeconds(60),
                Duration.ofSeconds(300),
                Duration.ofSeconds(10),
                Duration.ofSeconds(5),
                "data/keys/tss",
                false,
                false,
                false,
                false,
                false,
                2,
                10,
                Duration.ofSeconds(5),
                validateBlockSignatures,
                true,
                false,
                "",
                "",
                "",
                Duration.ofSeconds(60));
    }

    private static HintsPartialSignatureTransactionBody partialSigBody(final long constructionId) {
        return HintsPartialSignatureTransactionBody.newBuilder()
                .constructionId(constructionId)
                .message(MESSAGE)
                .partialSignature(PARTIAL_SIGNATURE)
                .build();
    }

    private static HintsConstruction constructionWith(
            final long constructionId, final PreprocessedKeys keys, final List<NodePartyId> nodePartyIds) {
        return HintsConstruction.newBuilder()
                .constructionId(constructionId)
                .hintsScheme(new HintsScheme(keys, nodePartyIds))
                .build();
    }

    @Test
    void becomesReadyOnceConstructionSet() {
        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, subject::constructionIdOrThrow);
        assertThrows(IllegalStateException.class, subject::verificationKeyOrThrow);

        subject.setConstruction(CONSTRUCTION);

        assertTrue(subject.isReady());

        assertEquals(CONSTRUCTION.constructionId(), subject.constructionIdOrThrow());
        assertEquals(VERIFICATION_KEY, subject.verificationKeyOrThrow());
    }

    @Test
    void incorporatingValidWorksAsExpected() {
        final Map<Integer, Bytes> expectedSignatures = Map.of(
                A_NODE_PARTY_ID.partyId(), signature,
                B_NODE_PARTY_ID.partyId(), signature,
                C_NODE_PARTY_ID.partyId(), signature,
                D_NODE_PARTY_ID.partyId(), signature);
        final var aggregateSignature = Bytes.wrap("AS");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregateSignature);

        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        final var future = signing.future();

        signing.incorporateValid(CRS, A_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        // Duplicates don't accumulate weight
        for (int i = 0; i < 10; i++) {
            signing.incorporateValid(CRS, A_NODE_PARTY_ID.nodeId(), signature);
            assertFalse(future.isDone());
        }
        assertFalse(future.isDone());
        signing.incorporateValid(CRS, B_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporateValid(CRS, C_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(future.isDone());
        signing.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);
        assertTrue(future.isDone());
        assertEquals(aggregateSignature, future.join());
        verify(signingMetrics).recordSignatureProduced(longThat(ms -> ms >= 0));
    }

    @Test
    void alwaysRequiresGreaterThanThreshold() {
        final var a = new NodePartyId(21L, 1, 5L);
        final var b = new NodePartyId(22L, 2, 5L);
        final var construction = HintsConstruction.newBuilder()
                .constructionId(3L)
                .hintsScheme(new HintsScheme(PREPROCESSED_KEYS, List.of(a, b)))
                .build();

        final Map<Integer, Bytes> expectedSignatures = Map.of(a.partyId(), signature, b.partyId(), signature);
        final var aggregateSignature = Bytes.wrap("AS3");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregateSignature);

        subject.setConstruction(construction);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        final var future = signing.future();

        // Exactly half (5 out of 10 total weight): should NOT complete, need strictly > 1/2
        signing.incorporateValid(CRS, a.nodeId(), signature);
        assertFalse(future.isDone());

        // One more signature gives us 10 out of 10: now it should complete
        signing.incorporateValid(CRS, b.nodeId(), signature);
        assertTrue(future.isDone());
        assertEquals(aggregateSignature, future.join());
        verify(signingMetrics).recordSignatureProduced(longThat(ms -> ms >= 0));
    }

    @Test
    void validateReturnsFalseWithoutAnActiveConstruction() {
        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verifyNoInteractions(library);
    }

    @Test
    void validateThrowsOnNullCrs() {
        subject.setConstruction(CONSTRUCTION);

        assertThrows(
                NullPointerException.class,
                () -> subject.validate(A_NODE_PARTY_ID.nodeId(), null, partialSigBody(CONSTRUCTION.constructionId())));
        verifyNoInteractions(library);
    }

    @Test
    void validateDelegatesToVerifyBlsForMatchingConstructionAndNode() {
        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(true);
        subject.setConstruction(CONSTRUCTION);

        assertTrue(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verify(library).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void validateReturnsFalseWhenVerifyBlsFails() {
        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(false);
        subject.setConstruction(CONSTRUCTION);

        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verify(library).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void validateReturnsFalseWhenConstructionIdDoesNotMatch() {
        subject.setConstruction(CONSTRUCTION);

        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId() + 1)));
        verify(library, never()).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void validateUsesPreviousConstructionAfterHandoff() {
        final var nextConstruction = constructionWith(
                2L,
                NEXT_PREPROCESSED_KEYS,
                List.of(
                        new NodePartyId(A_NODE_PARTY_ID.nodeId(), 22, A_NODE_PARTY_ID.partyWeight()),
                        B_NODE_PARTY_ID,
                        C_NODE_PARTY_ID,
                        D_NODE_PARTY_ID));
        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(true);

        subject.setConstruction(CONSTRUCTION);
        subject.setConstruction(nextConstruction);

        assertTrue(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verify(library).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
        verify(library, never()).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, NEXT_AGGREGATION_KEY, 22);
    }

    @Test
    void newSigningForConstructionUsesPreviousSnapshotAfterHandoff() {
        final var nextConstruction = constructionWith(
                2L,
                NEXT_PREPROCESSED_KEYS,
                List.of(
                        new NodePartyId(A_NODE_PARTY_ID.nodeId(), 22, A_NODE_PARTY_ID.partyWeight()),
                        B_NODE_PARTY_ID,
                        C_NODE_PARTY_ID,
                        D_NODE_PARTY_ID));

        subject.setConstruction(CONSTRUCTION);
        subject.setConstruction(nextConstruction);

        final var signing = subject.newSigningForConstruction(BLOCK_HASH, CONSTRUCTION.constructionId(), () -> {});

        assertNotNull(signing);
        assertEquals(CONSTRUCTION.constructionId(), signing.constructionId());
        assertEquals(VERIFICATION_KEY, signing.verificationKey());
    }

    @Test
    void newSigningForUnknownConstructionReturnsNull() {
        subject.setConstruction(CONSTRUCTION);

        assertNull(subject.newSigningForConstruction(BLOCK_HASH, CONSTRUCTION.constructionId() + 1, () -> {}));
    }

    @Test
    void validateReturnsFalseForUnknownNodeId() {
        subject.setConstruction(CONSTRUCTION);

        assertFalse(subject.validate(10_000L, CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verifyNoInteractions(library);
    }

    @Test
    void signingValidatePartialUsesCapturedSnapshotAfterHandoff() {
        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        subject.setConstruction(constructionWith(
                2L,
                NEXT_PREPROCESSED_KEYS,
                List.of(
                        new NodePartyId(A_NODE_PARTY_ID.nodeId(), 22, A_NODE_PARTY_ID.partyWeight()),
                        B_NODE_PARTY_ID,
                        C_NODE_PARTY_ID,
                        D_NODE_PARTY_ID)));

        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, BLOCK_HASH, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(true);

        assertTrue(signing.validatePartial(
                A_NODE_PARTY_ID.nodeId(),
                CRS,
                HintsPartialSignatureTransactionBody.newBuilder()
                        .constructionId(CONSTRUCTION.constructionId())
                        .message(BLOCK_HASH)
                        .partialSignature(PARTIAL_SIGNATURE)
                        .build()));
        verify(library).verifyBls(CRS, PARTIAL_SIGNATURE, BLOCK_HASH, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
        verify(library, never()).verifyBls(CRS, PARTIAL_SIGNATURE, BLOCK_HASH, NEXT_AGGREGATION_KEY, 22);
    }

    @Test
    void signingValidatePartialRejectsWrongConstructionOrMessage() {
        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});

        assertFalse(signing.validatePartial(
                A_NODE_PARTY_ID.nodeId(),
                CRS,
                HintsPartialSignatureTransactionBody.newBuilder()
                        .constructionId(CONSTRUCTION.constructionId() + 1)
                        .message(BLOCK_HASH)
                        .partialSignature(PARTIAL_SIGNATURE)
                        .build()));
        assertFalse(signing.validatePartial(
                A_NODE_PARTY_ID.nodeId(),
                CRS,
                HintsPartialSignatureTransactionBody.newBuilder()
                        .constructionId(CONSTRUCTION.constructionId())
                        .message(MESSAGE)
                        .partialSignature(PARTIAL_SIGNATURE)
                        .build()));
        verifyNoInteractions(library);
    }

    @Test
    void validatesAggregateSignatureWhenEnabledAndValid() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(configWithValidateBlockSignatures(true));
        final Map<Integer, Bytes> expectedSignatures = Map.of(D_NODE_PARTY_ID.partyId(), signature);
        final var aggregateSignature = Bytes.wrap("ASV");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregateSignature);
        given(library.verifyAggregate(aggregateSignature, BLOCK_HASH, VERIFICATION_KEY, 1L, 2L))
                .willReturn(true);

        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        final var future = signing.future();
        signing.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);

        assertTrue(future.isDone());
        assertEquals(aggregateSignature, future.join());
        verify(library).verifyAggregate(aggregateSignature, BLOCK_HASH, VERIFICATION_KEY, 1L, 2L);
        verify(signingMetrics).recordSignatureProduced(longThat(ms -> ms >= 0));
    }

    @Test
    void validatesAggregateSignatureWhenEnabledAndInvalid() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(configWithValidateBlockSignatures(true));
        final Map<Integer, Bytes> expectedSignatures = Map.of(D_NODE_PARTY_ID.partyId(), signature);
        final var aggregateSignature = Bytes.wrap("ASI");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregateSignature);
        given(library.verifyAggregate(aggregateSignature, BLOCK_HASH, VERIFICATION_KEY, 1L, 2L))
                .willReturn(false);

        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        final var future = signing.future();
        signing.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);

        assertTrue(future.isCompletedExceptionally());
        final var completion = assertThrows(CompletionException.class, future::join);
        assertInstanceOf(IllegalStateException.class, completion.getCause());
        assertEquals(
                HintsContext.INVALID_AGGREGATE_SIGNATURE_MESSAGE,
                completion.getCause().getMessage());
        verify(library).verifyAggregate(aggregateSignature, BLOCK_HASH, VERIFICATION_KEY, 1L, 2L);
        verify(signingMetrics, never()).recordSignatureProduced(longThat(ms -> ms >= 0));
    }

    @Test
    void nullAggregateSignatureCompletesExceptionallyWithoutVerification() {
        given(configuration.getConfigData(TssConfig.class)).willReturn(configWithValidateBlockSignatures(true));
        final Map<Integer, Bytes> expectedSignatures = Map.of(D_NODE_PARTY_ID.partyId(), signature);
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(null);

        subject.setConstruction(CONSTRUCTION);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        final var future = signing.future();
        signing.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);

        assertTrue(future.isCompletedExceptionally());
        verify(library, never()).verifyAggregate(any(), any(), any(), anyLong(), anyLong());
        verify(signingMetrics, never()).recordSignatureProduced(longThat(ms -> ms >= 0));
    }
}
