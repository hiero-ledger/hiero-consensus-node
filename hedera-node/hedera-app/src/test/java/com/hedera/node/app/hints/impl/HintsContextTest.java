// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.longThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.state.history.HistoryProofConstruction;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.spi.fixtures.util.LogCaptor;
import com.hedera.node.config.data.TssConfig;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
            .targetRosterHash(Bytes.wrap("generation target"))
            .constructionId(1L)
            .crsId(1L)
            .numParties(32)
            .hintsScheme(new HintsScheme(
                    PREPROCESSED_KEYS, List.of(A_NODE_PARTY_ID, B_NODE_PARTY_ID, C_NODE_PARTY_ID, D_NODE_PARTY_ID)))
            .build();
    private static final Bytes CRS = Bytes.wrap("CRS");
    private static final CRSState CRS_STATE = CRSState.newBuilder()
            .ceremonyId(1L)
            .numParties(32)
            .crs(CRS)
            .stage(CRSStage.COMPLETED)
            .build();
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
                true,
                true,
                2,
                10,
                Duration.ofSeconds(5),
                validateBlockSignatures,
                false,
                false);
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
                .targetRosterHash(Bytes.wrap("generation target"))
                .constructionId(constructionId)
                .crsId(1L)
                .numParties(32)
                .hintsScheme(new HintsScheme(keys, nodePartyIds))
                .build();
    }

    private static HintsConstruction nextConstruction() {
        return constructionWith(
                2L,
                NEXT_PREPROCESSED_KEYS,
                List.of(
                        new NodePartyId(A_NODE_PARTY_ID.nodeId(), 22, A_NODE_PARTY_ID.partyWeight()),
                        B_NODE_PARTY_ID,
                        C_NODE_PARTY_ID,
                        D_NODE_PARTY_ID));
    }

    private void setConstruction(final HintsConstruction construction, final CRSState crsState) {
        subject.setConstruction(construction, crsState, HintsGenerationTestUtils.weightsFor(construction));
    }

    @Test
    void bindsCrsToEachSigningAcrossHandoff() {
        final var nextCrs = CRS_STATE
                .copyBuilder()
                .ceremonyId(2L)
                .crs(Bytes.wrap("NEXT_CRS"))
                .build();
        final var next = nextConstruction().copyBuilder().crsId(2L).build();
        setConstruction(CONSTRUCTION, CRS_STATE);
        subject.onBlockStarted(10L);
        final var oldSigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        setConstruction(next, nextCrs);
        assertEquals(CRS, subject.crsForConstruction(CONSTRUCTION.constructionId()));
        assertEquals(nextCrs.crs(), subject.crsForConstruction(next.constructionId()));
        assertFalse(subject.validate(
                A_NODE_PARTY_ID.nodeId(), nextCrs.crs(), partialSigBody(CONSTRUCTION.constructionId())));
        oldSigning.incorporateValid(nextCrs.crs(), D_NODE_PARTY_ID.nodeId(), PARTIAL_SIGNATURE);
        assertFalse(oldSigning.future().isDone());
        verify(library, never()).aggregateSignatures(any(), any(), any(), any());
        final var expectedSignatures = Map.of(D_NODE_PARTY_ID.partyId(), PARTIAL_SIGNATURE);
        final var aggregate = Bytes.wrap("old construction aggregate");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregate);
        oldSigning.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), PARTIAL_SIGNATURE);
        assertEquals(aggregate, oldSigning.future().join());
        verify(library).aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures);
        oldSigning.cancel();
        subject.onBlockStarted(13L);
        assertNull(subject.crsForConstruction(CONSTRUCTION.constructionId()));
    }

    @Test
    void refusesIncompleteOrDifferentCrs() {
        assertThrows(
                IllegalArgumentException.class,
                () -> setConstruction(
                        CONSTRUCTION,
                        CRS_STATE
                                .copyBuilder()
                                .stage(CRSStage.GATHERING_CONTRIBUTIONS)
                                .build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> setConstruction(
                        CONSTRUCTION, CRS_STATE.copyBuilder().ceremonyId(2L).build()));
        assertThrows(
                IllegalArgumentException.class,
                () -> setConstruction(
                        CONSTRUCTION, CRS_STATE.copyBuilder().numParties(64).build()));
        assertFalse(subject.isReady());
    }

    @Test
    void refusesOversizedExistingCrsWithoutReplacingActiveSigning() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        final var oversized =
                CONSTRUCTION.copyBuilder().constructionId(2).numParties(128).build();
        final var oversizedCrs = CRS_STATE.copyBuilder().numParties(128).build();

        assertThrows(IllegalArgumentException.class, () -> setConstruction(oversized, oversizedCrs));

        assertTrue(subject.isReady());
        assertEquals(CONSTRUCTION.constructionId(), subject.constructionIdOrThrow());
        assertEquals(VERIFICATION_KEY, subject.verificationKeyOrThrow());
        assertEquals(CRS, subject.crsForConstruction(CONSTRUCTION.constructionId()));
    }

    @Test
    void refusesReservedLastPartyWithoutReplacingActiveSigning() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        final var invalid = CONSTRUCTION
                .copyBuilder()
                .constructionId(2)
                .numParties(64)
                .hintsScheme(new HintsScheme(PREPROCESSED_KEYS, List.of(new NodePartyId(1, 63, 1))))
                .build();
        final var crs = CRS_STATE.copyBuilder().numParties(64).build();

        assertThrows(IllegalArgumentException.class, () -> setConstruction(invalid, crs));

        assertEquals(CONSTRUCTION.constructionId(), subject.constructionIdOrThrow());
        assertEquals(VERIFICATION_KEY, subject.verificationKeyOrThrow());
    }

    @Test
    void becomesReadyOnceConstructionSet() {
        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, subject::constructionIdOrThrow);
        assertThrows(IllegalStateException.class, subject::verificationKeyOrThrow);

        setConstruction(CONSTRUCTION, CRS_STATE);

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

        setConstruction(CONSTRUCTION, CRS_STATE);

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
                .targetRosterHash(Bytes.wrap("generation target"))
                .constructionId(3L)
                .crsId(1L)
                .numParties(32)
                .hintsScheme(new HintsScheme(PREPROCESSED_KEYS, List.of(a, b)))
                .build();

        final Map<Integer, Bytes> expectedSignatures = Map.of(a.partyId(), signature, b.partyId(), signature);
        final var aggregateSignature = Bytes.wrap("AS3");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, expectedSignatures))
                .willReturn(aggregateSignature);

        setConstruction(construction, CRS_STATE);

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
    void rejectsWeightsFromADifferentGenerationWithoutReplacingTheActiveSnapshot() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        final var changed = constructionWith(
                2L, NEXT_PREPROCESSED_KEYS, CONSTRUCTION.hintsSchemeOrThrow().nodePartyIds());

        assertThrows(
                IllegalArgumentException.class,
                () -> subject.setConstruction(changed, CRS_STATE, HintsGenerationTestUtils.weightsFor(CONSTRUCTION)));

        assertEquals(CONSTRUCTION, subject.activeConstruction());
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,true"})
    void compatibilityModesUseSchemeOnlyWeightsAndNeedNoHistory(
            final boolean historyEnabled, final boolean forceMockSignatures) {
        final var a = new NodePartyId(1L, 1, 10L);
        final var b = new NodePartyId(2L, 2, 1L);
        final var c = new NodePartyId(3L, 3, 7L);
        final var construction = constructionWith(4L, PREPROCESSED_KEYS, List.of(a, b, c));
        final var config = HederaTestConfigBuilder.create()
                .withValue("tss.historyEnabled", historyEnabled)
                .withValue("tss.forceMockSignatures", forceMockSignatures)
                .withValue("tss.validateBlockSignatures", true)
                .getOrCreateConfig();
        given(configuration.getConfigData(TssConfig.class)).willReturn(config.getConfigData(TssConfig.class));
        final var aggregate = Bytes.wrap("ten of eighteen");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, Map.of(1, signature)))
                .willReturn(aggregate);
        given(library.verifyAggregate(aggregate, BLOCK_HASH, VERIFICATION_KEY, 1L, 2L))
                .willReturn(true);

        subject.setConstruction(construction, CRS_STATE);
        // An optional history argument must not change compatibility-mode behavior either.
        subject.setConstruction(
                construction,
                CRS_STATE,
                HintsGenerationWeights.from(
                        construction,
                        HintsGenerationTestUtils.historyFor(construction, Map.of(1L, 10L, 2L, 1L, 3L, 7L, 4L, 2L))));

        assertFalse(subject.requiresHistory());
        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        signing.incorporateValid(CRS, a.nodeId(), signature);
        assertEquals(aggregate, signing.future().join());
        verify(library).verifyAggregate(aggregate, BLOCK_HASH, VERIFICATION_KEY, 1L, 2L);
    }

    @Test
    void compatibilityModeRetainsLegacyEmptyIdentityAndZeroWeightScheme() {
        given(configuration.getConfigData(TssConfig.class))
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.forceMockSignatures", true)
                        .getOrCreateConfig()
                        .getConfigData(TssConfig.class));
        final var construction = constructionWith(4L, PREPROCESSED_KEYS, List.of())
                .copyBuilder()
                .targetRosterHash(Bytes.EMPTY)
                .build();

        subject.setConstruction(construction, CRS_STATE);

        assertTrue(subject.isReady());
        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        signing.incorporateValid(CRS, 1L, signature);
        assertFalse(signing.future().isDone());
        verifyNoInteractions(library);
        signing.cancel();
    }

    @Test
    void policyChangesKeepAlreadyOpenedSigningDenominatorsInBothDirections() {
        final var construction = constructionWith(
                4L,
                PREPROCESSED_KEYS,
                List.of(new NodePartyId(1L, 1, 10L), new NodePartyId(2L, 2, 1L), new NodePartyId(3L, 3, 7L)));
        final var history = HintsGenerationTestUtils.historyFor(construction, Map.of(1L, 10L, 2L, 1L, 3L, 7L, 4L, 2L));
        subject.setConstruction(construction, CRS_STATE, HintsGenerationWeights.from(construction, history));
        final var strictSigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        given(configuration.getConfigData(TssConfig.class))
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.forceMockSignatures", true)
                        .getOrCreateConfig()
                        .getConfigData(TssConfig.class));
        subject.setConstruction(construction, CRS_STATE);
        final var compatibilitySigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        given(configuration.getConfigData(TssConfig.class)).willReturn(defaultConfig());
        subject.setConstruction(construction, CRS_STATE, HintsGenerationWeights.from(construction, history));
        final var restoredStrictSigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        final var compatibilityAggregate = Bytes.wrap("legacy ten of eighteen");
        final var strictAggregate = Bytes.wrap("strict eleven of twenty");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, Map.of(1, signature)))
                .willReturn(compatibilityAggregate);
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, Map.of(1, signature, 2, signature)))
                .willReturn(strictAggregate);

        strictSigning.incorporateValid(CRS, 1L, signature);
        compatibilitySigning.incorporateValid(CRS, 1L, signature);
        restoredStrictSigning.incorporateValid(CRS, 1L, signature);
        assertFalse(strictSigning.future().isDone());
        assertFalse(restoredStrictSigning.future().isDone());
        assertEquals(compatibilityAggregate, compatibilitySigning.future().join());
        strictSigning.incorporateValid(CRS, 2L, signature);
        restoredStrictSigning.incorporateValid(CRS, 2L, signature);
        assertEquals(strictAggregate, strictSigning.future().join());
        assertEquals(strictAggregate, restoredStrictSigning.future().join());
    }

    @Test
    void switchingToStrictPolicyBlocksNewSigningUntilAMatchingHistoryGenerationIsInstalled() {
        final var construction = constructionWith(
                4L,
                PREPROCESSED_KEYS,
                List.of(new NodePartyId(1L, 1, 10L), new NodePartyId(2L, 2, 1L), new NodePartyId(3L, 3, 7L)));
        final var history = HintsGenerationTestUtils.historyFor(construction, Map.of(1L, 10L, 2L, 1L, 3L, 7L, 4L, 2L));
        given(configuration.getConfigData(TssConfig.class))
                .willReturn(HederaTestConfigBuilder.create()
                        .withValue("tss.forceMockSignatures", true)
                        .getOrCreateConfig()
                        .getConfigData(TssConfig.class));
        subject.setConstruction(construction, CRS_STATE);
        assertTrue(subject.isReady());
        final var compatibilitySigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        given(configuration.getConfigData(TssConfig.class)).willReturn(defaultConfig());

        assertFalse(subject.isReady());
        assertThrows(IllegalStateException.class, () -> subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {}));
        assertNull(subject.newSigningForConstruction(BLOCK_HASH, construction.constructionId(), () -> {}));
        assertThrows(
                HintsGenerationWeights.InvalidGenerationException.class,
                () -> subject.setConstruction(construction, CRS_STATE));
        assertFalse(subject.isReady());
        final var malformedHistory = history.copyBuilder()
                .targetRosterHash(Bytes.wrap("different generation"))
                .build();
        assertThrows(
                HintsGenerationWeights.InvalidGenerationException.class,
                () -> subject.setConstruction(
                        construction, CRS_STATE, HintsGenerationWeights.from(construction, malformedHistory)));
        assertFalse(subject.isReady());
        assertNull(subject.newSigningForConstruction(BLOCK_HASH, construction.constructionId(), () -> {}));
        final var compatibilityAggregate = Bytes.wrap("existing compatibility attempt");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, Map.of(1, signature)))
                .willReturn(compatibilityAggregate);
        compatibilitySigning.incorporateValid(CRS, 1L, signature);
        assertEquals(compatibilityAggregate, compatibilitySigning.future().join());

        subject.setConstruction(construction, CRS_STATE, HintsGenerationWeights.from(construction, history));

        assertTrue(subject.isReady());
        final var strictSigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        strictSigning.incorporateValid(CRS, 1L, signature);
        assertFalse(strictSigning.future().isDone());
        final var strictAggregate = Bytes.wrap("restored strict attempt");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, Map.of(1, signature, 2, signature)))
                .willReturn(strictAggregate);
        strictSigning.incorporateValid(CRS, 2L, signature);
        assertEquals(strictAggregate, strictSigning.future().join());
    }

    @Test
    void warnsOnSparseStrictActivationWithoutRepeatingOrWarningInCompatibilityMode() {
        final var construction = constructionWith(4L, PREPROCESSED_KEYS, List.of(new NodePartyId(1L, 1, 5L)));
        final var history = HintsGenerationTestUtils.historyFor(construction, Map.of(1L, 5L, 2L, 1L));
        final var weights = HintsGenerationWeights.from(construction, history);
        final var logs = new LogCaptor(LogManager.getLogger(HintsContext.class));
        try {
            subject.setConstruction(construction, CRS_STATE, weights);
            subject.setConstruction(construction, CRS_STATE, weights);
            subject.setConstruction(construction, CRS_STATE, HintsGenerationWeights.from(construction, history));
            assertEquals(1, logs.warnLogs().size());
            assertTrue(logs.warnLogs().getFirst().contains("scheme coverage 5/6"));
            assertTrue(logs.warnLogs()
                    .getFirst()
                    .contains("additional one third of the full generation being unavailable"));
            given(configuration.getConfigData(TssConfig.class))
                    .willReturn(HederaTestConfigBuilder.create()
                            .withValue("tss.historyEnabled", false)
                            .getOrCreateConfig()
                            .getConfigData(TssConfig.class));

            subject.setConstruction(construction, CRS_STATE);

            assertEquals(1, logs.warnLogs().size());
        } finally {
            logs.stopCapture();
        }
    }

    @Test
    void strictModeRejectsTheHistoryFreeOverloadWithGenerationDiagnostic() {
        final var failure = assertThrows(
                HintsGenerationWeights.InvalidGenerationException.class,
                () -> subject.setConstruction(CONSTRUCTION, CRS_STATE));
        assertTrue(failure.getMessage().contains("hinTS"));
        assertFalse(subject.isReady());
    }

    @Test
    void sparseSchemeRequiresMoreThanHalfOfTheCompleteGeneration() {
        final var a = new NodePartyId(1L, 1, 10L);
        final var b = new NodePartyId(2L, 2, 1L);
        final var c = new NodePartyId(3L, 3, 7L);
        final var construction = constructionWith(4L, PREPROCESSED_KEYS, List.of(a, b, c));
        final var completeWeights = Map.of(1L, 10L, 2L, 1L, 3L, 7L, 4L, 2L);
        final var history = HintsGenerationTestUtils.historyFor(construction, completeWeights);
        final var aggregate = Bytes.wrap("eleven of twenty");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, Map.of(1, signature, 2, signature)))
                .willReturn(aggregate);
        given(library.verifyAggregate(aggregate, BLOCK_HASH, VERIFICATION_KEY, 5L, 9L))
                .willReturn(true);
        given(configuration.getConfigData(TssConfig.class)).willReturn(configWithValidateBlockSignatures(true));
        subject.setConstruction(construction, CRS_STATE, HintsGenerationWeights.from(construction, history));

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        signing.incorporateValid(CRS, a.nodeId(), signature);
        assertFalse(signing.future().isDone(), "Ten of twenty cannot sign, even with only eighteen party weight");
        verify(library, never()).aggregateSignatures(any(), any(), any(), any());
        signing.incorporateValid(CRS, b.nodeId(), signature);

        assertEquals(aggregate, signing.future().join());
        verify(library).verifyAggregate(aggregate, BLOCK_HASH, VERIFICATION_KEY, 5L, 9L);
    }

    @Test
    void capturesDecreasedGenerationWeightsAcrossMixedHandoffAndRestart() {
        final var oldConstruction = constructionWith(
                4L,
                PREPROCESSED_KEYS,
                List.of(new NodePartyId(1L, 1, 10L), new NodePartyId(2L, 2, 1L), new NodePartyId(3L, 3, 7L)));
        final var next = constructionWith(
                5L,
                NEXT_PREPROCESSED_KEYS,
                List.of(new NodePartyId(1L, 1, 6L), new NodePartyId(2L, 2, 1L), new NodePartyId(3L, 3, 3L)));
        final var oldHistory =
                HintsGenerationTestUtils.historyFor(oldConstruction, Map.of(1L, 10L, 2L, 1L, 3L, 7L, 4L, 2L));
        final var nextHistory = HintsGenerationTestUtils.historyFor(next, Map.of(1L, 6L, 2L, 1L, 3L, 3L, 4L, 2L));
        final var oldWeights = HintsGenerationWeights.from(oldConstruction, oldHistory);
        final var nextWeights = HintsGenerationWeights.from(next, nextHistory);
        final var selected = Map.of(1, signature, 2, signature);
        final var oldAggregate = Bytes.wrap("old eleven of twenty");
        final var nextAggregate = Bytes.wrap("new seven of twelve");
        given(library.aggregateSignatures(CRS, AGGREGATION_KEY, VERIFICATION_KEY, selected))
                .willReturn(oldAggregate);
        given(library.aggregateSignatures(CRS, NEXT_AGGREGATION_KEY, NEXT_VERIFICATION_KEY, selected))
                .willReturn(nextAggregate);
        given(library.verifyAggregate(oldAggregate, BLOCK_HASH, VERIFICATION_KEY, 5L, 9L))
                .willReturn(true);
        given(library.verifyAggregate(nextAggregate, BLOCK_HASH, NEXT_VERIFICATION_KEY, 3L, 5L))
                .willReturn(true);
        given(configuration.getConfigData(TssConfig.class)).willReturn(configWithValidateBlockSignatures(true));
        subject.setConstruction(oldConstruction, CRS_STATE, oldWeights);
        subject.onBlockStarted(20L);
        final var oldSigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        subject.setConstruction(next, CRS_STATE, nextWeights);
        final var nextSigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});

        oldSigning.incorporateValid(CRS, 1L, signature);
        nextSigning.incorporateValid(CRS, 1L, signature);
        assertFalse(oldSigning.future().isDone(), "The old signing retains the complete twenty-weight denominator");
        assertFalse(nextSigning.future().isDone(), "The next signing needs more than six of twelve");
        oldSigning.incorporateValid(CRS, 2L, signature);
        nextSigning.incorporateValid(CRS, 2L, signature);
        assertEquals(oldAggregate, oldSigning.future().join());
        assertEquals(nextAggregate, nextSigning.future().join());

        final var restarted = new HintsContext(library, configProvider, signingMetrics);
        restarted.setConstruction(next, CRS_STATE, HintsGenerationWeights.from(next, nextHistory));
        final var afterRestart = restarted.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        afterRestart.incorporateValid(CRS, 1L, signature);
        assertFalse(afterRestart.future().isDone(), "Restart restores the complete twelve-weight denominator");
        afterRestart.incorporateValid(CRS, 2L, signature);
        assertEquals(nextAggregate, afterRestart.future().join());
        verify(library).verifyAggregate(oldAggregate, BLOCK_HASH, VERIFICATION_KEY, 5L, 9L);
        verify(library, org.mockito.Mockito.times(2))
                .verifyAggregate(nextAggregate, BLOCK_HASH, NEXT_VERIFICATION_KEY, 3L, 5L);
    }

    @Test
    void validateReturnsFalseWithoutAnActiveConstruction() {
        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verifyNoInteractions(library);
    }

    @Test
    void validateThrowsOnNullCrs() {
        setConstruction(CONSTRUCTION, CRS_STATE);

        assertThrows(
                NullPointerException.class,
                () -> subject.validate(A_NODE_PARTY_ID.nodeId(), null, partialSigBody(CONSTRUCTION.constructionId())));
        verifyNoInteractions(library);
    }

    @Test
    void validateDelegatesToVerifyBlsForMatchingConstructionAndNode() {
        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(true);
        setConstruction(CONSTRUCTION, CRS_STATE);

        assertTrue(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verify(library).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void validateReturnsFalseWhenVerifyBlsFails() {
        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(false);
        setConstruction(CONSTRUCTION, CRS_STATE);

        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verify(library).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void validateReturnsFalseWhenConstructionIdDoesNotMatch() {
        setConstruction(CONSTRUCTION, CRS_STATE);

        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId() + 1)));
        verify(library, never()).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void validateUsesPreviousConstructionAfterHandoff() {
        final var nextConstruction = nextConstruction();
        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willReturn(true);

        setConstruction(CONSTRUCTION, CRS_STATE);
        subject.onBlockStarted(10L);
        setConstruction(nextConstruction, CRS_STATE);

        assertTrue(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verify(library).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
        verify(library, never()).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, NEXT_AGGREGATION_KEY, 22);
    }

    @Test
    void newSigningForConstructionUsesPreviousSnapshotAfterHandoff() {
        final var nextConstruction = nextConstruction();

        setConstruction(CONSTRUCTION, CRS_STATE);
        subject.onBlockStarted(10L);
        setConstruction(nextConstruction, CRS_STATE);

        final var signing = subject.newSigningForConstruction(BLOCK_HASH, CONSTRUCTION.constructionId(), () -> {});

        assertNotNull(signing);
        assertEquals(CONSTRUCTION.constructionId(), signing.constructionId());
        assertEquals(VERIFICATION_KEY, signing.verificationKey());
    }

    @Test
    void equalReparsedGenerationDoesNotWaitForTheSigningWriteLock() throws Exception {
        final var constructionBytes = HintsConstruction.PROTOBUF.toBytes(CONSTRUCTION);
        final var crsBytes = CRSState.PROTOBUF.toBytes(CRS_STATE);
        final var history = HintsGenerationTestUtils.historyFor(CONSTRUCTION);
        final var historyBytes = HistoryProofConstruction.PROTOBUF.toBytes(history);
        final var firstConstruction = HintsConstruction.PROTOBUF.parse(constructionBytes);
        final var secondConstruction = HintsConstruction.PROTOBUF.parse(constructionBytes);
        final var firstCrs = CRSState.PROTOBUF.parse(crsBytes);
        final var secondCrs = CRSState.PROTOBUF.parse(crsBytes);
        final var firstHistory = HistoryProofConstruction.PROTOBUF.parse(historyBytes);
        final var secondHistory = HistoryProofConstruction.PROTOBUF.parse(historyBytes);
        assertNotSame(firstConstruction, secondConstruction);
        assertNotSame(firstCrs, secondCrs);
        assertNotSame(firstHistory, secondHistory);
        assertEquals(firstConstruction, secondConstruction);
        assertEquals(firstCrs, secondCrs);
        assertEquals(firstHistory, secondHistory);
        subject.setConstruction(
                firstConstruction, firstCrs, HintsGenerationWeights.from(firstConstruction, firstHistory));
        final var entered = new java.util.concurrent.CountDownLatch(1);
        final var release = new java.util.concurrent.CountDownLatch(1);
        given(library.verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId()))
                .willAnswer(invocation -> {
                    entered.countDown();
                    assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
                    return true;
                });
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var verification = executor.submit(() ->
                    subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                final var activation = executor.submit(() -> subject.setConstruction(
                        secondConstruction, secondCrs, HintsGenerationWeights.from(secondConstruction, secondHistory)));
                activation.get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertTrue(verification.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    void oldAndNewConstructionVerificationCanOverlapWithoutCacheResets() throws Exception {
        setConstruction(CONSTRUCTION, CRS_STATE);
        subject.onBlockStarted(10L);
        final var next = nextConstruction();
        setConstruction(next, CRS_STATE);
        final var entered = new java.util.concurrent.CountDownLatch(2);
        final var release = new java.util.concurrent.CountDownLatch(1);
        given(library.verifyBls(any(), any(), any(), any(), anyInt())).willAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS));
            return true;
        });
        try (final var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            final var oldVerification = executor.submit(() ->
                    subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
            final var newVerification = executor.submit(
                    () -> subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(next.constructionId())));
            try {
                assertTrue(
                        entered.await(5, java.util.concurrent.TimeUnit.SECONDS),
                        "Both construction verifications must enter before either completes");
            } finally {
                release.countDown();
            }
            assertTrue(oldVerification.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(newVerification.get(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        subject.onBlockStarted(13L);
        verify(library, never()).resetCache();
    }

    @Test
    void previousConstructionExpiresAfterThreeMixedBlocks() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        subject.onBlockStarted(10L);
        setConstruction(nextConstruction(), CRS_STATE);

        assertTrue(subject.acceptsConstruction(CONSTRUCTION.constructionId()));
        subject.onBlockStarted(11L);
        assertTrue(subject.acceptsConstruction(CONSTRUCTION.constructionId()));
        subject.onBlockStarted(12L);
        assertTrue(subject.acceptsConstruction(CONSTRUCTION.constructionId()));
        subject.onBlockStarted(13L);

        assertFalse(subject.acceptsConstruction(CONSTRUCTION.constructionId()));
        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        assertNull(subject.newSigningForConstruction(BLOCK_HASH, CONSTRUCTION.constructionId(), () -> {}));
        verify(library, never()).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void previousConstructionIsRejectedWithoutBlockStartedWindow() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        setConstruction(nextConstruction(), CRS_STATE);

        assertFalse(subject.acceptsConstruction(CONSTRUCTION.constructionId()));
        assertFalse(subject.validate(A_NODE_PARTY_ID.nodeId(), CRS, partialSigBody(CONSTRUCTION.constructionId())));
        assertNull(subject.newSigningForConstruction(BLOCK_HASH, CONSTRUCTION.constructionId(), () -> {}));
        verify(library, never()).verifyBls(CRS, PARTIAL_SIGNATURE, MESSAGE, AGGREGATION_KEY, A_NODE_PARTY_ID.partyId());
    }

    @Test
    void repeatedActivationPreservesThePreviousGenerationAndOriginalWindow() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        subject.onBlockStarted(10L);
        final var next = nextConstruction();
        setConstruction(next, CRS_STATE);
        subject.onBlockStarted(11L);
        setConstruction(next, CRS_STATE);
        final var rebound = next.copyBuilder()
                .targetRosterHash(Bytes.wrap("transport-only rebinding"))
                .build();
        setConstruction(rebound, CRS_STATE);
        assertEquals(rebound, subject.activeConstruction());
        final var oldSigning = subject.newSigningForConstruction(BLOCK_HASH, CONSTRUCTION.constructionId(), () -> {});
        assertNotNull(oldSigning);
        final var aggregate = Bytes.wrap("old signing after repeated activation");
        given(library.aggregateSignatures(
                        CRS, AGGREGATION_KEY, VERIFICATION_KEY, Map.of(D_NODE_PARTY_ID.partyId(), signature)))
                .willReturn(aggregate);

        oldSigning.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);

        assertEquals(aggregate, oldSigning.future().join());
        subject.onBlockStarted(13L);
        assertFalse(
                subject.acceptsConstruction(CONSTRUCTION.constructionId()), "Reactivation must not extend the window");
    }

    @Test
    void rejectsChangesToAnAlreadyActiveConstructionId() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        final var differentKeys = constructionWith(
                CONSTRUCTION.constructionId(),
                NEXT_PREPROCESSED_KEYS,
                CONSTRUCTION.hintsSchemeOrThrow().nodePartyIds());
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.setConstruction(
                        differentKeys, CRS_STATE, HintsGenerationTestUtils.weightsFor(differentKeys)));
        final var changedMembership =
                HintsGenerationTestUtils.historyFor(CONSTRUCTION, Map.of(1L, 1L, 3L, 1L, 7L, 1L, 9L, 9L, 100L, 100L));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.setConstruction(
                        CONSTRUCTION, CRS_STATE, HintsGenerationWeights.from(CONSTRUCTION, changedMembership)));
        assertThrows(
                IllegalArgumentException.class,
                () -> subject.setConstruction(
                        CONSTRUCTION,
                        CRS_STATE.copyBuilder().crs(Bytes.wrap("different CRS")).build(),
                        HintsGenerationTestUtils.weightsFor(CONSTRUCTION)));
        assertEquals(CONSTRUCTION, subject.activeConstruction());
    }

    @Test
    void clearingLearnedStateRemovesReadinessAndEveryAcceptedGeneration() {
        setConstruction(CONSTRUCTION, CRS_STATE);
        final var oldSigning = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        subject.onBlockStarted(10L);
        final var next = nextConstruction();
        setConstruction(next, CRS_STATE);
        assertTrue(subject.acceptsConstruction(CONSTRUCTION.constructionId()));

        subject.clear();

        assertFalse(subject.isReady());
        assertNull(subject.activeConstruction());
        assertFalse(subject.acceptsConstruction(CONSTRUCTION.constructionId()));
        assertFalse(subject.acceptsConstruction(next.constructionId()));
        assertThrows(IllegalStateException.class, subject::verificationKeyOrThrow);
        oldSigning.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);
        assertFalse(oldSigning.future().isDone());
        verifyNoInteractions(library);
        setConstruction(next, CRS_STATE);
        assertFalse(subject.acceptsConstruction(CONSTRUCTION.constructionId()));
        assertTrue(subject.acceptsConstruction(next.constructionId()));
        oldSigning.cancel();
    }

    @Test
    void newSigningForUnknownConstructionReturnsNull() {
        setConstruction(CONSTRUCTION, CRS_STATE);

        assertNull(subject.newSigningForConstruction(BLOCK_HASH, CONSTRUCTION.constructionId() + 1, () -> {}));
    }

    @Test
    void validateReturnsFalseForUnknownNodeId() {
        setConstruction(CONSTRUCTION, CRS_STATE);

        assertFalse(subject.validate(10_000L, CRS, partialSigBody(CONSTRUCTION.constructionId())));
        verifyNoInteractions(library);
    }

    @Test
    void signingValidatePartialUsesCapturedSnapshotAfterHandoff() {
        setConstruction(CONSTRUCTION, CRS_STATE);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        subject.onBlockStarted(10L);
        setConstruction(nextConstruction(), CRS_STATE);

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
        setConstruction(CONSTRUCTION, CRS_STATE);

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

        setConstruction(CONSTRUCTION, CRS_STATE);

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

        setConstruction(CONSTRUCTION, CRS_STATE);

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

        setConstruction(CONSTRUCTION, CRS_STATE);

        final var signing = subject.newSigningForActiveConstruction(BLOCK_HASH, () -> {});
        final var future = signing.future();
        signing.incorporateValid(CRS, D_NODE_PARTY_ID.nodeId(), signature);

        assertTrue(future.isCompletedExceptionally());
        verify(library, never()).verifyAggregate(any(), any(), any(), anyLong(), anyLong());
        verify(signingMetrics, never()).recordSignatureProduced(longThat(ms -> ms >= 0));
    }
}
