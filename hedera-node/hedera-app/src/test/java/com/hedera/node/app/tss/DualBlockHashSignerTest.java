// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss;

import static com.hedera.node.app.blocks.BlockHashSigner.Request.LIST_OF_PARTIAL_SIGNATURES;
import static com.hedera.node.app.blocks.BlockHashSigner.Request.SUCCINCT_SIGNATURE;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.hedera.node.app.blocks.BlockHashSigner;
import com.hedera.node.app.hints.impl.BlockHashSigning;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.hints.impl.RsaContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DualBlockHashSignerTest {
    private static final Bytes BLOCK_HASH = Bytes.wrap("block-hash");
    private static final Bytes RSA_SIGNATURES = Bytes.wrap("rsa-signatures");

    @Mock
    private RsaContext rsaContext;

    @Mock
    private TssSubmissions submissions;

    @Mock
    private BlockHashSigner succinctSignatureDelegate;

    @Mock
    private RsaContext.Signing rsaSigning;

    @Mock
    private HintsContext.Signing hintsSigning;

    private ConcurrentHashMap<Bytes, BlockHashSigning> rsaSignings;
    private DualBlockHashSigner subject;

    @BeforeEach
    void setUp() {
        rsaSignings = new ConcurrentHashMap<>();
        subject = new DualBlockHashSigner(rsaContext, rsaSignings, submissions, succinctSignatureDelegate);
    }

    @Test
    void isReadyRequiresBothSuccinctDelegateAndRsaContext() {
        given(succinctSignatureDelegate.isReady()).willReturn(true);
        given(rsaContext.isReady()).willReturn(true);

        assertTrue(subject.isReady());

        given(rsaContext.isReady()).willReturn(false);

        assertFalse(subject.isReady());

        given(succinctSignatureDelegate.isReady()).willReturn(false);

        assertFalse(subject.isReady());
    }

    @Test
    void delegatesSuccinctSignatureRequests() {
        final var attempt =
                new BlockHashSigner.Attempt(Bytes.EMPTY, null, CompletableFuture.completedFuture(Bytes.EMPTY));
        given(succinctSignatureDelegate.sign(BLOCK_HASH, SUCCINCT_SIGNATURE)).willReturn(attempt);

        assertSame(attempt, subject.sign(BLOCK_HASH, SUCCINCT_SIGNATURE));
        verifyNoInteractions(rsaContext, submissions);
    }

    @Test
    void listOfPartialSignaturesThrowsIfRsaContextIsNotReady() {
        given(rsaContext.isReady()).willReturn(false);

        assertThrows(IllegalStateException.class, () -> subject.sign(BLOCK_HASH, LIST_OF_PARTIAL_SIGNATURES));
        verifyNoInteractions(submissions);
    }

    @Test
    void listOfPartialSignaturesCreatesRsaSigningSubmitsSignatureAndRemovesFromMapOnCompletion() {
        given(rsaContext.isReady()).willReturn(true);
        given(rsaContext.newSigning(eq(BLOCK_HASH), any(Runnable.class))).willReturn(rsaSigning);
        final var submissionFuture = CompletableFuture.<Void>completedFuture(null);
        given(submissions.submitRsaSignature(BLOCK_HASH)).willReturn(submissionFuture);
        given(rsaSigning.future()).willReturn(CompletableFuture.completedFuture(RSA_SIGNATURES));

        final var attempt = subject.sign(BLOCK_HASH, LIST_OF_PARTIAL_SIGNATURES);

        assertNull(attempt.verificationKey());
        assertNull(attempt.chainOfTrustProof());
        assertSame(RSA_SIGNATURES, attempt.signatureFuture().join());
        assertSame(submissionFuture, attempt.submissionFuture());
        assertSame(rsaSigning, rsaSignings.get(BLOCK_HASH));
        final var onCompletion = ArgumentCaptor.forClass(Runnable.class);
        verify(rsaContext).newSigning(eq(BLOCK_HASH), onCompletion.capture());
        verify(submissions).submitRsaSignature(BLOCK_HASH);

        onCompletion.getValue().run();

        assertFalse(rsaSignings.containsKey(BLOCK_HASH));
    }

    @Test
    void listOfPartialSignaturesReusesExistingRsaSigning() {
        rsaSignings.put(BLOCK_HASH, rsaSigning);
        given(rsaContext.isReady()).willReturn(true);
        final var submissionFuture = CompletableFuture.<Void>completedFuture(null);
        given(submissions.submitRsaSignature(BLOCK_HASH)).willReturn(submissionFuture);
        given(rsaSigning.future()).willReturn(CompletableFuture.completedFuture(RSA_SIGNATURES));

        final var attempt = subject.sign(BLOCK_HASH, LIST_OF_PARTIAL_SIGNATURES);

        assertSame(RSA_SIGNATURES, attempt.signatureFuture().join());
        assertSame(submissionFuture, attempt.submissionFuture());
        verify(rsaContext, never()).newSigning(any(), any(Runnable.class));
        verify(submissions).submitRsaSignature(BLOCK_HASH);
    }

    @Test
    void listOfPartialSignaturesRejectsNonRsaSigningInMap() {
        rsaSignings.put(BLOCK_HASH, hintsSigning);
        given(rsaContext.isReady()).willReturn(true);

        assertThrows(IllegalStateException.class, () -> subject.sign(BLOCK_HASH, LIST_OF_PARTIAL_SIGNATURES));

        verify(rsaContext, never()).newSigning(any(), any(Runnable.class));
        verifyNoInteractions(submissions);
    }

    @Test
    void rejectsNullArguments() {
        assertThrows(NullPointerException.class, () -> subject.sign(null, SUCCINCT_SIGNATURE));
        assertThrows(NullPointerException.class, () -> subject.sign(BLOCK_HASH, null));
    }
}
