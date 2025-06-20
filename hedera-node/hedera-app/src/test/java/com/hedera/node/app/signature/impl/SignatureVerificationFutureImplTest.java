// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hiero.base.crypto.VerificationStatus.INVALID;
import static org.hiero.base.crypto.VerificationStatus.VALID;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.base.crypto.TransactionSignature;
import org.hiero.base.crypto.VerificationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Tests for {@link SignatureVerificationFuture} */
@ExtendWith(MockitoExtension.class)
final class SignatureVerificationFutureImplTest implements Scenarios {

    private SignatureVerificationFutureImpl sut;
    private final AtomicReference<CompletableFuture<Void>> cryptoFuture = new AtomicReference<>();
    private final AtomicReference<VerificationStatus> cryptoResult = new AtomicReference<>();

    @BeforeEach
    void setUp(@Mock final TransactionSignature sig) {
        this.sut = new SignatureVerificationFutureImpl(ALICE.keyInfo().publicKey(), null, sig);
        lenient().when(sig.getSignatureStatus()).thenAnswer(i -> cryptoResult.get());
    }

    private void signatureSubmittedToCryptoEngine() {
        cryptoFuture.set(new CompletableFuture<>());
    }

    private void cryptoEngineReturnsResult(final VerificationStatus result) {
        cryptoResult.set(result);
        final var future = cryptoFuture.get();
        if (future == null) cryptoFuture.set(new CompletableFuture<>());
        cryptoFuture.get().complete(null);
    }

    @Nested
    @DisplayName("Construction")
    @ExtendWith(MockitoExtension.class)
    final class ConstructionTests {
        /** Null arguments are not allowed to the constructor. */
        @Test
        @DisplayName("Giving a null key or map to the constructor throws")
        void nullArgsThrows(@Mock final TransactionSignature sig) {
            // Given a null key, when we pass that null list to the constructor, then it throws an NPE
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new SignatureVerificationFutureImpl(null, Bytes.EMPTY, sig))
                    .isInstanceOf(NullPointerException.class);
            // Given a null map, when we pass that null list to the constructor, then it throws an NPE
            //noinspection DataFlowIssue
            assertThatThrownBy(() -> new SignatureVerificationFutureImpl(Key.DEFAULT, Bytes.EMPTY, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Null Account is permitted")
        void nullAccountIsPermitted(@Mock final TransactionSignature sig) {
            assertThatNoException().isThrownBy(() -> new SignatureVerificationFutureImpl(Key.DEFAULT, null, sig));
        }

        @Test
        @DisplayName("Hollow alias matches that provided to the constructor")
        void aliasIsSet(@Mock final TransactionSignature sig) {
            var sut = new SignatureVerificationFutureImpl(Key.DEFAULT, null, sig);
            assertThat(sut.evmAlias()).isNull();

            sut = new SignatureVerificationFutureImpl(
                    Key.DEFAULT, ERIN.account().alias(), sig);
            assertThat(sut.evmAlias()).isSameAs(ERIN.account().alias());
        }

        @Test
        @DisplayName("Key matches that provided to the constructor")
        void keyIsSet(@Mock final TransactionSignature sig) {
            var sut = new SignatureVerificationFutureImpl(ALICE.keyInfo().publicKey(), null, sig);
            assertThat(sut.key()).isSameAs(ALICE.keyInfo().publicKey());
        }

        @Test
        @DisplayName("TransactionSignature matches that provided to the constructor")
        void txSigIsSet(@Mock final TransactionSignature sig) {
            var sut = new SignatureVerificationFutureImpl(ALICE.keyInfo().publicKey(), null, sig);
            assertThat(sut.txSig()).isSameAs(sig);
        }
    }

    @Nested
    @DisplayName("Cancellation")
    @ExtendWith(MockitoExtension.class)
    final class CancellationTests {
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("If canceled after done, nothing happens")
        void cancelAfterDoneDoesNothing(final boolean mayInterruptIfRunning) {
            // Given an instance with this sig that is already complete
            signatureSubmittedToCryptoEngine();
            cryptoEngineReturnsResult(VALID);

            // When we call `cancel`
            final var wasCanceled = sut.cancel(mayInterruptIfRunning);

            // Then we find that it was not canceled and didn't pretend to
            assertThat(wasCanceled).isFalse();
            assertThat(sut.isCancelled()).isFalse();
            assertThat(sut)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(true);
            assertThat(sut.isDone()).isTrue();
        }
    }

    @Nested
    @DisplayName("Get")
    final class GetTests {
        /**
         * The {@link SignatureVerificationFutureImpl} will succeed if the {@link TransactionSignature} completes
         * and succeeds.
         */
        @Test
        @DisplayName("Success if the TransactionSignature completes successfully")
        void successIfFutureSucceeds() {
            // Given an instance with this sig that is complete
            signatureSubmittedToCryptoEngine();
            cryptoEngineReturnsResult(VALID);

            // Then we find that the SignatureVerificationResult is done, and returns "true" from its get methods
            assertThat(sut)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(true);
            assertThat(sut.isDone()).isTrue();
        }

        @Test
        @DisplayName("The instance does not pass if the TransactionSignature fails")
        void failureIfSignatureCheckFails() {
            // Given an instance with this sig that is complete but INVALID
            signatureSubmittedToCryptoEngine();
            cryptoEngineReturnsResult(INVALID);

            // Then we find that the SignatureVerificationResult is done, and does not pass
            assertThat(sut)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(false);
            assertThat(sut.isDone()).isTrue();
        }
    }
}
