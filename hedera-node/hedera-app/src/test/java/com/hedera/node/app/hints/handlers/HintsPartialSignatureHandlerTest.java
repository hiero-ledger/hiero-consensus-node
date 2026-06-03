// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.ReadableHintsStore;
import com.hedera.node.app.hints.impl.BlockHashSigning;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.hints.impl.RsaContext;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.store.StoreFactory;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.config.data.TssConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsPartialSignatureHandlerTest {
    private static final long NODE_ID = 123L;
    private static final long CONSTRUCTION_ID = 456L;
    private static final Bytes CRS = Bytes.wrap("crs");
    private static final Bytes MESSAGE = Bytes.wrap("msg");
    private static final Bytes PARTIAL_SIGNATURE = Bytes.wrap("sig");

    private static final HintsPartialSignatureTransactionBody OP = HintsPartialSignatureTransactionBody.newBuilder()
            .constructionId(CONSTRUCTION_ID)
            .message(MESSAGE)
            .partialSignature(PARTIAL_SIGNATURE)
            .build();

    private static final HintsPartialSignatureTransactionBody RSA_OP = HintsPartialSignatureTransactionBody.newBuilder()
            .constructionId(RsaContext.CONSTRUCTION_ID)
            .message(MESSAGE)
            .partialSignature(PARTIAL_SIGNATURE)
            .build();

    @Mock
    private HintsContext hintsContext;

    @Mock
    private RsaContext rsaContext;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private NodeInfo nodeInfo;

    @Mock
    private ReadableHintsStore hintsStore;

    @Mock
    private StoreFactory storeFactory;

    @Mock
    private Configuration configuration;

    @Mock
    private TssConfig tssConfig;

    @Mock
    private HintsContext.Signing signing;

    @Mock
    private RsaContext.Signing rsaSigning;

    private ConcurrentMap<Bytes, BlockHashSigning> signings;

    private ConcurrentMap<Bytes, BlockHashSigning> rsaSignings;

    HintsPartialSignatureHandler subject;

    @BeforeEach
    void setUp() {
        signings = new ConcurrentHashMap<>();
        rsaSignings = new ConcurrentHashMap<>();
        subject = new HintsPartialSignatureHandler(
                Duration.ofSeconds(2), signings, rsaSignings, hintsContext, rsaContext);

        final var body = TransactionBody.newBuilder().hintsPartialSignature(OP).build();
        lenient().when(preHandleContext.body()).thenReturn(body);
        lenient().when(handleContext.body()).thenReturn(body);

        lenient().when(preHandleContext.creatorInfo()).thenReturn(nodeInfo);
        lenient().when(handleContext.creatorInfo()).thenReturn(nodeInfo);
        lenient().when(nodeInfo.nodeId()).thenReturn(NODE_ID);

        lenient().when(preHandleContext.configuration()).thenReturn(configuration);
        lenient().when(handleContext.configuration()).thenReturn(configuration);
        lenient().when(configuration.getConfigData(TssConfig.class)).thenReturn(tssConfig);

        lenient().when(preHandleContext.createStore(ReadableHintsStore.class)).thenReturn(hintsStore);
        lenient().when(handleContext.storeFactory()).thenReturn(storeFactory);
        lenient().when(storeFactory.readableStore(ReadableHintsStore.class)).thenReturn(hintsStore);
        lenient().when(hintsStore.crsIfKnown()).thenReturn(CRS);
        lenient().when(signing.constructionId()).thenReturn(CONSTRUCTION_ID);
    }

    @Test
    void pureChecksDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(
                        null, new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), hintsContext, rsaContext));
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(
                        Duration.ofSeconds(2), null, new ConcurrentHashMap<>(), hintsContext, rsaContext));
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(
                        Duration.ofSeconds(2), new ConcurrentHashMap<>(), null, hintsContext, rsaContext));
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(
                        Duration.ofSeconds(2), new ConcurrentHashMap<>(), new ConcurrentHashMap<>(), null, rsaContext));
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(
                        Duration.ofSeconds(2),
                        new ConcurrentHashMap<>(),
                        new ConcurrentHashMap<>(),
                        hintsContext,
                        null));
    }

    @Test
    void pureChecksRejectsNullContext() {
        assertThrows(NullPointerException.class, () -> subject.pureChecks(null));
    }

    @Test
    void preHandleRejectsNullContext() {
        assertThrows(NullPointerException.class, () -> subject.preHandle(null));
    }

    @Test
    void preHandleRejectsUnknownCrs() {
        given(hintsStore.crsIfKnown()).willReturn(null);

        assertThrows(NullPointerException.class, () -> subject.preHandle(preHandleContext));
    }

    @Test
    void preHandleSwallowsInternalExceptions() {
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void preHandleNonDeterministicValidSignatureCreatesAndIncorporatesSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), any(Runnable.class)))
                .willReturn(signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext).newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), any(Runnable.class));
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
        assertSame(signing, signings.get(MESSAGE));
    }

    @Test
    void preHandleNonDeterministicValidSignatureReusesExistingSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(signing.validatePartial(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        signings.put(MESSAGE, signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext, never()).validate(anyLong(), any(), any());
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void preHandleNonDeterministicMismatchedExistingSigningDoesNotIncorporate() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(signing.constructionId()).willReturn(CONSTRUCTION_ID + 1);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        signings.put(MESSAGE, signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
        verify(signing, never()).incorporateValid(any(), anyLong(), any());
    }

    @Test
    void preHandleNonDeterministicInvalidSignatureDoesNotIncorporate() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(false);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
        assertTrue(signings.isEmpty());
    }

    @Test
    void preHandleNonDeterministicValidRsaSignatureCreatesAndIncorporatesSigning() {
        givenRsaOp();
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(rsaContext.validate(NODE_ID, RSA_OP)).willReturn(true);
        given(rsaContext.newSigning(eq(MESSAGE), any(Runnable.class))).willReturn(rsaSigning);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(rsaContext).validate(NODE_ID, RSA_OP);
        verify(rsaContext).newSigning(eq(MESSAGE), any(Runnable.class));
        verify(rsaSigning).incorporateValid(Bytes.EMPTY, NODE_ID, PARTIAL_SIGNATURE);
        verifyNoInteractions(hintsContext);
        assertSame(rsaSigning, rsaSignings.get(MESSAGE));
        assertTrue(signings.isEmpty());
    }

    @Test
    void preHandleNonDeterministicInvalidRsaSignatureDoesNotIncorporate() {
        givenRsaOp();
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(rsaContext.validate(NODE_ID, RSA_OP)).willReturn(false);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(rsaContext).validate(NODE_ID, RSA_OP);
        verify(rsaContext, never()).newSigning(any(), any());
        assertTrue(rsaSignings.isEmpty());
        assertTrue(signings.isEmpty());
    }

    @Test
    void preHandleDeterministicRsaSignatureDoesNothing() {
        givenRsaOp();
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verifyNoInteractions(rsaContext);
        verifyNoInteractions(hintsContext);
        assertTrue(rsaSignings.isEmpty());
        assertTrue(signings.isEmpty());
    }

    @Test
    void deterministicFlowCachesValidResultFromPreHandleForHandle() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), any(Runnable.class)))
                .willReturn(signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        subject.handle(handleContext);

        verify(hintsContext, times(1)).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext).newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), any(Runnable.class));
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void deterministicFlowCachesInvalidResultFromPreHandleForHandle() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(false);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        subject.handle(handleContext);

        verify(hintsContext, times(1)).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
        assertTrue(signings.isEmpty());
    }

    @Test
    void handleRejectsNullContext() {
        assertThrows(NullPointerException.class, () -> subject.handle(null));
    }

    @Test
    void handleRejectsUnknownCrs() {
        given(hintsStore.crsIfKnown()).willReturn(null);

        assertThrows(NullPointerException.class, () -> subject.handle(handleContext));
    }

    @Test
    void handleNonDeterministicSignaturesDoesNothing() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);

        subject.handle(handleContext);

        verify(hintsContext, never()).validate(anyLong(), any(), any());
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
        assertTrue(signings.isEmpty());
    }

    @Test
    void handleNonDeterministicRsaSignatureDoesNothing() {
        givenRsaOp();
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);

        subject.handle(handleContext);

        verifyNoInteractions(rsaContext);
        verify(handleContext, never()).storeFactory();
        assertTrue(rsaSignings.isEmpty());
        assertTrue(signings.isEmpty());
    }

    @Test
    void handleDeterministicValidRsaSignatureCreatesAndIncorporatesSigning() {
        givenRsaOp();
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(rsaContext.validate(NODE_ID, RSA_OP)).willReturn(true);
        given(rsaContext.newSigning(eq(MESSAGE), any(Runnable.class))).willReturn(rsaSigning);

        subject.handle(handleContext);

        verify(rsaContext).validate(NODE_ID, RSA_OP);
        verify(rsaContext).newSigning(eq(MESSAGE), any(Runnable.class));
        verify(rsaSigning).incorporateValid(Bytes.EMPTY, NODE_ID, PARTIAL_SIGNATURE);
        verify(handleContext, never()).storeFactory();
        assertSame(rsaSigning, rsaSignings.get(MESSAGE));
        assertTrue(signings.isEmpty());
    }

    @Test
    void handleDeterministicValidRsaSignatureReusesExistingSigning() {
        givenRsaOp();
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(rsaContext.validate(NODE_ID, RSA_OP)).willReturn(true);
        rsaSignings.put(MESSAGE, rsaSigning);

        subject.handle(handleContext);

        verify(rsaContext, never()).newSigning(any(), any());
        verify(rsaSigning).incorporateValid(Bytes.EMPTY, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void handleDeterministicInvalidRsaSignatureDoesNotIncorporate() {
        givenRsaOp();
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(rsaContext.validate(NODE_ID, RSA_OP)).willReturn(false);

        subject.handle(handleContext);

        verify(rsaContext).validate(NODE_ID, RSA_OP);
        verify(rsaContext, never()).newSigning(any(), any());
        verifyNoInteractions(rsaSigning);
        assertTrue(rsaSignings.isEmpty());
    }

    @Test
    void handleDeterministicValidSignatureCreatesAndIncorporatesSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), any(Runnable.class)))
                .willReturn(signing);

        subject.handle(handleContext);

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext).newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), any(Runnable.class));
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void handleDeterministicValidSignatureReusesExistingSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(signing.validatePartial(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        signings.put(MESSAGE, signing);

        subject.handle(handleContext);

        verify(hintsContext, never()).validate(anyLong(), any(), any());
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void handleDeterministicExceptionsDuringValidationAreTreatedAsInvalid() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> subject.handle(handleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
    }

    @Test
    void preHandleCompletionCallbackRemovesSigningEntry() {
        final var completionCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), completionCaptor.capture()))
                .willReturn(signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        assertSame(signing, signings.get(MESSAGE));

        completionCaptor.getValue().run();

        assertTrue(signings.isEmpty());
    }

    @Test
    void handleCompletionCallbackRemovesSigningEntry() {
        final var completionCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigningForConstruction(eq(MESSAGE), eq(CONSTRUCTION_ID), completionCaptor.capture()))
                .willReturn(signing);

        subject.handle(handleContext);
        assertSame(signing, signings.get(MESSAGE));

        completionCaptor.getValue().run();

        assertTrue(signings.isEmpty());
    }

    @Test
    void rsaCompletionCallbackRemovesSigningEntry() {
        givenRsaOp();
        final var completionCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(rsaContext.validate(NODE_ID, RSA_OP)).willReturn(true);
        given(rsaContext.newSigning(eq(MESSAGE), completionCaptor.capture())).willReturn(rsaSigning);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        assertSame(rsaSigning, rsaSignings.get(MESSAGE));

        completionCaptor.getValue().run();

        assertTrue(rsaSignings.isEmpty());
    }

    @Test
    void deterministicPreHandleDoesNotIncorporateSignatures() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigningForConstruction(any(), anyLong(), any());
        verifyNoInteractions(signing);
        assertTrue(signings.isEmpty());
    }

    private void givenRsaOp() {
        final var body =
                TransactionBody.newBuilder().hintsPartialSignature(RSA_OP).build();
        lenient().when(preHandleContext.body()).thenReturn(body);
        lenient().when(handleContext.body()).thenReturn(body);
    }
}
