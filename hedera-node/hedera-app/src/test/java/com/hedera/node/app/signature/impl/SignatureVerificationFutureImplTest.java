// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature.impl;

import static com.swirlds.common.crypto.VerificationStatus.INVALID;
import static com.swirlds.common.crypto.VerificationStatus.VALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.signature.SignatureVerificationFuture;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.crypto.TransactionSignature;
import com.swirlds.common.crypto.VerificationStatus;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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
    void setUp(@Mock final TransactionSignature sig) throws InterruptedException {
        this.sut = new SignatureVerificationFutureImpl(ALICE.keyInfo().publicKey(), null, sig);
        lenient().when(sig.waitForFuture()).then(i -> {
            //noinspection StatementWithEmptyBody
            while (cryptoFuture.get() == null) {
                /* spin */
            }
            return cryptoFuture.get();
        });
        lenient().when(sig.getFuture()).thenAnswer(i -> cryptoFuture.get());
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

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("If canceled after canceled, nothing happens")
        void cancelAfterCancelDoesNothing(final boolean mayInterruptIfRunning) {
            // Given an instance with this sig that is NOT already complete
            signatureSubmittedToCryptoEngine();

            // When we call cancel twice
            final var wasCanceled1 = sut.cancel(mayInterruptIfRunning);
            final var wasCanceled2 = sut.cancel(mayInterruptIfRunning);

            // Then we find that the FIRST time it was canceled, but the SECOND time it was already canceled
            assertThat(wasCanceled1).isTrue();
            assertThat(wasCanceled2).isFalse();
            assertThat(sut.isCancelled()).isTrue();
            assertThat(sut.isDone())
                    .isTrue(); // Even though the future was not "DONE" this future is because of cancel!
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Canceled before task is submitted to crypto engine")
        void cancelBeforeSubmit(final boolean mayInterruptIfRunning) {
            // Given an instance with this sig that has not been submitted to the crypto engine
            // When we call cancel and THEN it is submitted
            final var wasCanceled = sut.cancel(mayInterruptIfRunning);
            signatureSubmittedToCryptoEngine();

            // Then we find that our future was canceled, but the one created by the crypto engine is not.
            assertThat(wasCanceled).isTrue();
            assertThat(sut.isCancelled()).isTrue();
            assertThat(sut.isDone())
                    .isTrue(); // Even though the future was not "DONE" this future is because of cancel!
            assertThat(cryptoFuture.get().isCancelled()).isFalse();
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("Canceled after future is received from crypto engine but before completion")
        void cancelAfterSubmit(final boolean mayInterruptIfRunning) {
            // Given an instance with this sig that has been submitted to the crypto engine
            signatureSubmittedToCryptoEngine();

            // When we call cancel
            final var wasCanceled = sut.cancel(mayInterruptIfRunning);

            // Then we find that our future was canceled, AND the one from the crypto engine is too.
            assertThat(wasCanceled).isTrue();
            assertThat(sut.isCancelled()).isTrue();
            assertThat(sut.isDone())
                    .isTrue(); // Even though the future was not "DONE" this future is because of cancel!
            assertThat(cryptoFuture.get().isCancelled()).isTrue();
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
        @DisplayName("Get with timout will timeout until TransactionSignature future is available")
        void getWithTimeoutWillTimeoutUntilResultsAreAvailable() {
            // Given an instance with this sig that is NOT YET submitted to the crypto engine
            // When we block on the get method, then it doesn't complete without a timeout
            assertThat(sut).isNotDone().failsWithin(1, TimeUnit.MILLISECONDS);

            // And when we then submit it to the crypto engine, but it has not yet completed
            signatureSubmittedToCryptoEngine();

            // Then we still time out
            assertThat(sut).isNotDone().failsWithin(10, TimeUnit.MILLISECONDS);

            // And when the crypto engine completes
            cryptoEngineReturnsResult(VALID);

            // Then we find that the SignatureVerificationResult is done
            assertThat(sut)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(true);
            assertThat(sut.isDone()).isTrue();
        }

        @Test
        @Disabled("This test is flaky")
        @DisplayName("Get method blocks until TransactionSignature future is available")
        @SuppressWarnings("java:S2925") // suppressing the warning about Thread.sleep usage in tests
        void getBlocksUntilResultsAreAvailable() throws InterruptedException {
            // Given an instance with this sig that is NOT YET submitted to the crypto engine
            // When we block on the get method
            final var aboutToBlock = new CountDownLatch(1);
            final var futureIsDone = new CountDownLatch(1);
            final var th = new Thread(() -> {
                try {
                    aboutToBlock.countDown();
                    sut.get();
                    futureIsDone.countDown();
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            });
            th.start();

            // Then it doesn't complete yet
            assertThat(aboutToBlock.await(50, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(futureIsDone.getCount()).isEqualTo(1);

            // And when we have submitted it to the crypto engine
            signatureSubmittedToCryptoEngine();

            // Then it still hasn't completed
            Thread.sleep(1); // just to increase the chance that the background thread does some work
            assertThat(futureIsDone.getCount()).isEqualTo(1);

            // But when it does complete
            cryptoEngineReturnsResult(VALID);

            // Then we find that the SignatureVerificationResult is done
            assertThat(futureIsDone.await(50, TimeUnit.MILLISECONDS)).isTrue();
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
