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
import com.hedera.node.app.hints.impl.HintsContext;
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

    @Mock
    private HintsContext hintsContext;

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

    private ConcurrentMap<Bytes, HintsContext.Signing> signings;

    HintsPartialSignatureHandler subject;

    @BeforeEach
    void setUp() {
        signings = new ConcurrentHashMap<>();
        subject = new HintsPartialSignatureHandler(Duration.ofSeconds(2), signings, hintsContext);

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

        lenient().when(hintsContext.constructionIdOrThrow()).thenReturn(CONSTRUCTION_ID);
    }

    @Test
    void pureChecksDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void constructorRejectsNullDependencies() {
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(null, new ConcurrentHashMap<>(), hintsContext));
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(Duration.ofSeconds(2), null, hintsContext));
        assertThrows(
                NullPointerException.class,
                () -> new HintsPartialSignatureHandler(Duration.ofSeconds(2), new ConcurrentHashMap<>(), null));
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
        given(hintsContext.constructionIdOrThrow()).willThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
    }

    @Test
    void preHandleNonDeterministicValidSignatureCreatesAndIncorporatesSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigning(eq(MESSAGE), any(Runnable.class))).willReturn(signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext).newSigning(eq(MESSAGE), any(Runnable.class));
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
        assertSame(signing, signings.get(MESSAGE));
    }

    @Test
    void preHandleNonDeterministicValidSignatureReusesExistingSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        signings.put(MESSAGE, signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext, never()).newSigning(any(), any());
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void preHandleNonDeterministicInvalidSignatureDoesNotIncorporate() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(false);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigning(any(), any());
        assertTrue(signings.isEmpty());
    }

    @Test
    void deterministicFlowCachesValidResultFromPreHandleForHandle() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigning(eq(MESSAGE), any(Runnable.class))).willReturn(signing);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        subject.handle(handleContext);

        verify(hintsContext, times(1)).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext).newSigning(eq(MESSAGE), any(Runnable.class));
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
        verify(hintsContext, never()).newSigning(any(), any());
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
        verify(hintsContext, never()).newSigning(any(), any());
        assertTrue(signings.isEmpty());
    }

    @Test
    void handleDeterministicValidSignatureCreatesAndIncorporatesSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigning(eq(MESSAGE), any(Runnable.class))).willReturn(signing);

        subject.handle(handleContext);

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext).newSigning(eq(MESSAGE), any(Runnable.class));
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void handleDeterministicValidSignatureReusesExistingSigning() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        signings.put(MESSAGE, signing);

        subject.handle(handleContext);

        verify(hintsContext, never()).newSigning(any(), any());
        verify(signing).incorporateValid(CRS, NODE_ID, PARTIAL_SIGNATURE);
    }

    @Test
    void handleDeterministicExceptionsDuringValidationAreTreatedAsInvalid() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willThrow(new IllegalStateException("boom"));

        assertDoesNotThrow(() -> subject.handle(handleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigning(any(), any());
    }

    @Test
    void preHandleCompletionCallbackRemovesSigningEntry() {
        final var completionCaptor = ArgumentCaptor.forClass(Runnable.class);
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(false);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);
        given(hintsContext.newSigning(eq(MESSAGE), completionCaptor.capture())).willReturn(signing);

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
        given(hintsContext.newSigning(eq(MESSAGE), completionCaptor.capture())).willReturn(signing);

        subject.handle(handleContext);
        assertSame(signing, signings.get(MESSAGE));

        completionCaptor.getValue().run();

        assertTrue(signings.isEmpty());
    }

    @Test
    void deterministicPreHandleDoesNotIncorporateSignatures() {
        given(tssConfig.useDeterministicHintsSignatures()).willReturn(true);
        given(hintsContext.validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class)))
                .willReturn(true);

        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));

        verify(hintsContext).validate(eq(NODE_ID), eq(CRS), any(HintsPartialSignatureTransactionBody.class));
        verify(hintsContext, never()).newSigning(any(), any());
        verifyNoInteractions(signing);
        assertTrue(signings.isEmpty());
    }
}
