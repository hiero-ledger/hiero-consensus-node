// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.signature;

import static java.util.Collections.emptyList;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.KeyList;
import com.hedera.node.app.signature.impl.SignatureVerificationImpl;
import com.hedera.node.app.spi.fixtures.Scenarios;
import com.hedera.node.app.spi.signatures.SignatureVerification;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.junit.jupiter.MockitoExtension;

final class CompoundSignatureVerificationFutureTest implements Scenarios {

    private CompletableFuture<SignatureVerification> aliceFuture;
    private CompletableFuture<SignatureVerification> bobFuture;
    private CompletableFuture<SignatureVerification> erinFuture;
    private CompoundSignatureVerificationFuture subject;

    @BeforeEach
    void setUp() {
        Key key = Key.newBuilder()
                .keyList(KeyList.newBuilder()
                        .keys(
                                ALICE.keyInfo().publicKey(),
                                BOB.keyInfo().publicKey(),
                                ERIN.keyInfo().publicKey()))
                .build();
        aliceFuture = new CompletableFuture<>();
        bobFuture = new CompletableFuture<>();
        erinFuture = new CompletableFuture<>();

        final List<Future<SignatureVerification>> futures = List.of(aliceFuture, bobFuture, erinFuture);
        subject = new CompoundSignatureVerificationFuture(key, null, futures, 0);
    }

    private void completeAlice(boolean pass) {
        aliceFuture.complete(new SignatureVerificationImpl(ALICE.keyInfo().publicKey(), null, pass));
    }

    private void completeBob(boolean pass) {
        bobFuture.complete(new SignatureVerificationImpl(BOB.keyInfo().publicKey(), null, pass));
    }

    private void completeErin(boolean pass) {
        erinFuture.complete(new SignatureVerificationImpl(ERIN.keyInfo().publicKey(), null, pass));
    }

    @Test
    @DisplayName("Key and Futures are required")
    @SuppressWarnings("DataFlowIssue")
    void keyAndFuturesAreRequired() {
        final var key = Key.DEFAULT;
        final var evmAlias = ERIN.account().alias();
        final List<Future<SignatureVerification>> futures = emptyList();
        assertThatThrownBy(() -> new CompoundSignatureVerificationFuture(null, evmAlias, futures, 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompoundSignatureVerificationFuture(key, evmAlias, null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Futures cannot be empty")
    void futuresCannotBeEmpty() {
        final var key = Key.DEFAULT;
        final var evmAlias = ERIN.account().alias();
        final List<Future<SignatureVerification>> futures = emptyList();
        assertThatThrownBy(() -> new CompoundSignatureVerificationFuture(key, evmAlias, futures, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Account and Key match that provided to the constructor")
    void accountAndKeyAreSet() {
        var key = ALICE.keyInfo().publicKey();
        List<Future<SignatureVerification>> futures =
                List.of(completedFuture(new SignatureVerificationImpl(key, null, true)));
        var subject = new CompoundSignatureVerificationFuture(key, null, futures, 0);
        assertThat(subject.evmAlias()).isNull();
        assertThat(subject.key()).isSameAs(key);

        final var alias = ERIN.account().alias();
        key = ERIN.keyInfo().publicKey();
        futures = List.of(completedFuture(new SignatureVerificationImpl(key, alias, true)));
        subject = new CompoundSignatureVerificationFuture(key, alias, futures, 0);
        assertThat(subject.evmAlias()).isSameAs(alias);
        assertThat(subject.key()).isSameAs(key);
    }

    @Nested
    @DisplayName("Cancellation")
    @ExtendWith(MockitoExtension.class)
    final class CancellationTests {
        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("If canceled after done, nothing happens")
        void cancelAfterDoneDoesNothing(final boolean mayInterruptIfRunning) {
            // Given an instance where all futures are already complete
            completeAlice(true);
            completeBob(true);
            completeErin(true);

            // When we call `cancel`
            final var wasCanceled = subject.cancel(mayInterruptIfRunning);

            // Then we find that it was not canceled and didn't pretend to
            assertThat(wasCanceled).isFalse();
            assertThat(subject.isCancelled()).isFalse();
            assertThat(subject)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(true);
            assertThat(subject.isDone()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        @DisplayName("If canceled after canceled, nothing happens")
        void cancelAfterCancelDoesNothing(final boolean mayInterruptIfRunning) {
            // Given an instance where there is at least one future that is NOT already complete
            completeAlice(true);
            completeBob(true); // ERIN is not complete

            // When we call cancel twice
            final var wasCanceled1 = subject.cancel(mayInterruptIfRunning);
            final var wasCanceled2 = subject.cancel(mayInterruptIfRunning);

            // Then we find that the FIRST time it was canceled, but the SECOND time it was already canceled
            assertThat(wasCanceled1).isTrue();
            assertThat(wasCanceled2).isFalse();
            assertThat(subject.isCancelled()).isTrue();
            assertThat(subject.isDone())
                    .isTrue(); // Even though the future was not "DONE" this future is because of cancel!
        }
    }

    @Nested
    @DisplayName("Done")
    final class DoneTest {
        @Test
        @DisplayName("Done if all futures are done")
        void done() {
            // Given an instance where no futures are done

            // At first the future is not done, until after each future completes
            assertThat(subject.isDone()).isFalse();
            completeAlice(true);
            assertThat(subject.isDone()).isFalse();
            completeBob(true);
            assertThat(subject.isDone()).isFalse();
            completeErin(true);
            assertThat(subject.isDone()).isTrue();
        }
    }

    @Nested
    @DisplayName("Get")
    final class GetTests {
        @Test
        @DisplayName("Success if the futures all complete successfully")
        void successIfAllFuturesSucceed() {
            // Given an instance where all futures are done and everything passes
            completeAlice(true);
            completeBob(true);
            completeErin(true);

            // Then we find that the future is done, and returns "true" from its get method
            assertThat(subject)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(true);
            assertThat(subject.isDone()).isTrue();
        }

        @Test
        @DisplayName("Get with timout will timeout until all futures are done")
        void getWithTimeoutWillTimeoutUntilResultsAreAvailable() {
            // Given an instance where some of the futures are not yet complete,
            completeBob(true); // Alice is not complete
            completeErin(true);

            // When we block on the get method, then it doesn't complete without a timeout
            assertThat(subject).isNotDone().failsWithin(1, TimeUnit.MILLISECONDS);

            // And when the final future completes
            completeAlice(true);

            // Then we find that the result is done
            assertThat(subject)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(true);
            assertThat(subject.isDone()).isTrue();
        }

        @Test
        @DisplayName("Get without timout will block until all futures are done")
        void getBlocksUntilResultsAreAvailable() throws InterruptedException {
            // Given an instance where some of the futures are not yet complete,
            completeBob(true); // Alice is not complete
            completeErin(true);

            // When we block on the get method
            final var aboutToBlock = new CountDownLatch(1);
            final var futureIsDone = new CountDownLatch(1);
            final var th = new Thread(() -> {
                try {
                    aboutToBlock.countDown();
                    subject.get();
                    futureIsDone.countDown();
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            });
            th.start();

            // Then it doesn't complete yet
            assertThat(aboutToBlock.await(250, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(futureIsDone.getCount()).isEqualTo(1);

            // And when the final future completes, then the get method returns
            completeAlice(true);
            assertThat(futureIsDone.await(50, TimeUnit.MILLISECONDS)).isTrue();

            // Then we find that the result is done
            assertThat(futureIsDone.await(50, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(subject)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(true);
            assertThat(subject.isDone()).isTrue();
        }
    }

    @Nested
    @DisplayName("Pass / Fail")
    final class PassFailTest {
        @ParameterizedTest
        @CsvSource(
                textBlock =
                        """
                true, true, true, true
                true, true, false, false
                true, false, true, false
                true, false, false, false
                false, true, true, false
                false, true, false, false
                false, false, true, false
                false, false, false, false
                """)
        @DisplayName("Pass if all futures pass")
        void passIfAllFuturesPass(
                final boolean alicePasses, final boolean bobPasses, final boolean erinPasses, final boolean expected) {
            // Given an instance where all futures are done and some of them pass as per the args
            completeAlice(alicePasses);
            completeBob(bobPasses);
            completeErin(erinPasses);

            // Then we find that the future is done, and returns expected value from its "passed" method
            assertThat(subject)
                    .succeedsWithin(1, TimeUnit.SECONDS)
                    .extracting("passed")
                    .isEqualTo(expected);
            assertThat(subject.isDone()).isTrue();
        }

        @ParameterizedTest
        @CsvSource(
                textBlock =
                        """
                true, true, true, true
                true, true, false, false
                true, false, true, false
                true, false, false, false
                false, true, true, false
                false, true, false, false
                false, false, true, false
                false, false, false, false
                """)
        @DisplayName("Pass if all futures pass")
        void passIfAllFuturesPassBlocking(
                final boolean alicePasses, final boolean bobPasses, final boolean erinPasses, final boolean expected)
                throws ExecutionException, InterruptedException {
            // Given an instance where all futures are done and some of them pass as per the args
            completeAlice(alicePasses);
            completeBob(bobPasses);
            completeErin(erinPasses);

            // Then we find that the future is done, and returns expected value from its "passed" method
            // but this time using the blocking "get" call
            final var verification = subject.get();
            assertThat(verification.passed()).isEqualTo(expected);
            assertThat(subject.isDone()).isTrue();
        }
    }
}
