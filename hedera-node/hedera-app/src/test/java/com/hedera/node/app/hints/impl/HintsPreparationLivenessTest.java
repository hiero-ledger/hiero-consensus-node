// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static com.hedera.node.app.hints.impl.HintsControllerImpl.decodeCrsUpdate;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.cryptography.hints.AggregationAndVerificationKeys;
import com.hedera.hapi.node.state.hints.CRSStage;
import com.hedera.hapi.node.state.hints.CRSState;
import com.hedera.hapi.node.state.hints.HintsConstruction;
import com.hedera.hapi.node.state.hints.HintsScheme;
import com.hedera.hapi.node.state.hints.NodePartyId;
import com.hedera.hapi.node.state.hints.PreprocessedKeys;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.services.auxiliary.hints.HintsPartialSignatureTransactionBody;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Checks both Java routing and actual native cache isolation while ACTIVE signing remains live. */
class HintsPreparationLivenessTest {
    private static final Bytes MESSAGE = Bytes.wrap("ACTIVE signing during NEXT preparation");
    private static final int ACTIVE_CAPACITY = 4;
    private static final long ACTIVE_CONSTRUCTION_ID = 1;

    enum Preparation {
        PREPROCESS,
        COMPUTE_HINTS,
        VALIDATE_HINTS
    }

    private static Stream<Arguments> preparationModes() {
        return Arrays.stream(Preparation.values())
                .flatMap(operation -> Stream.of(Arguments.of(operation, false), Arguments.of(operation, true)));
    }

    @ParameterizedTest(name = "{0}, deterministic signatures={1}")
    @MethodSource("preparationModes")
    void activePreHandleRejectsInvalidSharesAndAggregatesWhilePreparationIsBlocked(
            final Preparation operation, final boolean deterministicSignatures) throws Exception {
        final var realLibrary = new HintsLibraryImpl();
        final var active = activeScheme(realLibrary);
        final var keyLibrary = mock(IsolatedHintsLibrary.class);
        final var preprocessingLibrary = mock(IsolatedHintsLibrary.class);
        final var library = new HintsLibraryImpl(keyLibrary, preprocessingLibrary);
        final var entered = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        switch (operation) {
            case PREPROCESS ->
                when(preprocessingLibrary.preprocess(any(), any(), any(), any(), anyInt()))
                        .thenAnswer(invocation -> {
                            awaitRelease(entered, release);
                            return active.keys();
                        });
            case COMPUTE_HINTS ->
                when(keyLibrary.computeHints(any(), any(), anyInt(), anyInt())).thenAnswer(invocation -> {
                    awaitRelease(entered, release);
                    return active.hint().toByteArray();
                });
            case VALIDATE_HINTS ->
                when(keyLibrary.validateHintsKey(any(), any(), anyInt(), anyInt()))
                        .thenAnswer(invocation -> {
                            awaitRelease(entered, release);
                            return true;
                        });
        }
        final var config = HederaTestConfigBuilder.create()
                .withValue("tss.useDeterministicHintsSignatures", deterministicSignatures)
                .getOrCreateConfig();
        final var signingContext = new HintsContext(library, () -> config, mock(HintsSigningMetrics.class));
        signingContext.setConstruction(active.construction(), active.crsState());
        final var signing = signingContext.newSigningForActiveConstruction(MESSAGE, () -> {});
        final var signings = new ConcurrentHashMap<Bytes, BlockHashSigning>();
        signings.put(MESSAGE, signing);
        final var handler = new HintsPartialSignatureHandler(
                Duration.ofSeconds(2), signings, new ConcurrentHashMap<>(), signingContext, mock(RsaContext.class));
        final var context = mock(PreHandleContext.class);
        final var handleContext = mock(HandleContext.class);
        final var node = mock(NodeInfo.class);
        when(node.nodeId()).thenReturn(0L);
        when(context.creatorInfo()).thenReturn(node);
        when(context.configuration()).thenReturn(config);
        when(handleContext.creatorInfo()).thenReturn(node);
        when(handleContext.configuration()).thenReturn(config);
        final var nextCrs = realLibrary.newCrs((short) 8);
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var preparation = executor.submit(() -> switch (operation) {
                case PREPROCESS ->
                    library.preprocess(
                            nextCrs, new TreeMap<>(Map.of(0, active.hint())), new TreeMap<>(Map.of(0, 1L)), 8);
                case COMPUTE_HINTS -> library.computeHints(nextCrs, active.privateKey(), 0, 8);
                case VALIDATE_HINTS -> library.validateHintsKey(nextCrs, active.hint(), 0, 8);
            });
            try {
                assertTrue(entered.await(10, SECONDS), "NEXT preparation must be running before ACTIVE pre-handle");
                final var invalidSignature = library.signBls(MESSAGE, library.newBlsPrivateKey());
                when(context.body()).thenReturn(partialBody(invalidSignature));
                when(handleContext.body()).thenReturn(partialBody(invalidSignature));
                executor.submit(() -> {
                            handler.preHandle(context);
                            return null;
                        })
                        .get(10, SECONDS);
                executor.submit(() -> handler.handle(handleContext)).get(10, SECONDS);
                assertFalse(signing.future().isDone(), "An invalid share must not count toward the threshold");
                when(context.body()).thenReturn(partialBody(active.signature()));
                when(handleContext.body()).thenReturn(partialBody(active.signature()));
                executor.submit(() -> {
                            handler.preHandle(context);
                            return null;
                        })
                        .get(10, SECONDS);
                if (deterministicSignatures) {
                    assertFalse(signing.future().isDone(), "Deterministic mode waits for handle to aggregate");
                }
                executor.submit(() -> handler.handle(handleContext)).get(10, SECONDS);
                final var aggregate = signing.future().get(10, SECONDS);
                assertTrue(library.verifyAggregate(
                        aggregate, MESSAGE, Bytes.wrap(active.keys().verificationKey()), 1, 3));
                assertFalse(preparation.isDone(), "ACTIVE pre-handle and aggregation must finish before NEXT");
            } finally {
                release.countDown();
            }
            preparation.get(10, SECONDS);
        } finally {
            signing.cancel();
        }
    }

    @Test
    @Timeout(90)
    void actualNativePreprocessingDoesNotBlockActiveSigningOrReplacementKeyValidation() throws Exception {
        final var library = new HintsLibraryImpl();
        final var active = activeScheme(library);
        final int nextCapacity = 32;
        final var nextCrs = contributedCrs(library, nextCapacity);
        final var hints = new TreeMap<Integer, Bytes>();
        final var weights = new TreeMap<Integer, Long>();
        // Enough native work to observe overlap without asserting a hardware-specific runtime.
        for (int party = 0; party < nextCapacity - 2; party++) {
            hints.put(party, library.computeHints(nextCrs, library.newBlsPrivateKey(), party, nextCapacity));
            weights.put(party, 1L);
        }
        final var preparationThread = new AtomicReference<Thread>();
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var preparation = executor.submit(() -> {
                preparationThread.set(Thread.currentThread());
                return library.preprocess(nextCrs, hints, weights, nextCapacity);
            });
            awaitNativePreprocessing(preparationThread, preparation);
            assertTrue(library.verifyBls(
                    active.crsState().crs(),
                    active.signature(),
                    MESSAGE,
                    Bytes.wrap(active.keys().aggregationKey()),
                    0));
            final var aggregate = library.aggregateSignatures(
                    active.crsState().crs(),
                    Bytes.wrap(active.keys().aggregationKey()),
                    Bytes.wrap(active.keys().verificationKey()),
                    Map.of(0, active.signature()));
            assertTrue(library.verifyAggregate(
                    aggregate, MESSAGE, Bytes.wrap(active.keys().verificationKey()), 1, 3));
            assertTrue(library.validateHintsKey(nextCrs, hints.get(0), 0, nextCapacity));
            assertFalse(
                    preparation.isDone(),
                    "ACTIVE signing and replacement key validation must complete during native preprocessing");
            assertNotNull(preparation.get(60, SECONDS));
        }
    }

    private static void awaitRelease(final CountDownLatch entered, final CountDownLatch release)
            throws InterruptedException {
        entered.countDown();
        assertTrue(release.await(30, SECONDS), "The test must release NEXT preparation");
    }

    private static void awaitNativePreprocessing(
            final AtomicReference<Thread> preparationThread, final Future<?> preparation) throws Exception {
        final long deadline = System.nanoTime() + SECONDS.toNanos(15);
        while (System.nanoTime() < deadline && !preparation.isDone()) {
            final var thread = preparationThread.get();
            if (thread != null
                    && Arrays.stream(thread.getStackTrace())
                            .anyMatch(frame -> frame.isNativeMethod()
                                    && frame.getMethodName().equals("preprocessImpl"))) {
                return;
            }
            Thread.sleep(1);
        }
        if (preparation.isDone()) {
            assertNotNull(preparation.get());
        }
        fail("Did not observe NEXT executing native preprocessing");
    }

    private static ActiveScheme activeScheme(final HintsLibraryImpl library) {
        final var crs = contributedCrs(library, ACTIVE_CAPACITY);
        final var privateKey = library.newBlsPrivateKey();
        final var hint = library.computeHints(crs, privateKey, 0, ACTIVE_CAPACITY);
        final var keys =
                library.preprocess(crs, new TreeMap<>(Map.of(0, hint)), new TreeMap<>(Map.of(0, 1L)), ACTIVE_CAPACITY);
        assertNotNull(keys);
        final var crsState = CRSState.newBuilder()
                .ceremonyId(1)
                .stage(CRSStage.COMPLETED)
                .numParties(ACTIVE_CAPACITY)
                .crs(crs)
                .build();
        final var construction = HintsConstruction.newBuilder()
                .constructionId(ACTIVE_CONSTRUCTION_ID)
                .crsId(1)
                .numParties(ACTIVE_CAPACITY)
                .hintsScheme(new HintsScheme(
                        new PreprocessedKeys(Bytes.wrap(keys.aggregationKey()), Bytes.wrap(keys.verificationKey())),
                        List.of(new NodePartyId(0, 0, 1))))
                .build();
        return new ActiveScheme(crsState, construction, privateKey, hint, keys, library.signBls(MESSAGE, privateKey));
    }

    private static Bytes contributedCrs(final HintsLibraryImpl library, final int capacity) {
        final var seed = library.newCrs((short) capacity);
        final var entropy = new byte[32];
        entropy[0] = (byte) capacity;
        return decodeCrsUpdate(seed.length(), library.updateCrs(seed, Bytes.wrap(entropy)))
                .crs();
    }

    private static TransactionBody partialBody(final Bytes signature) {
        return TransactionBody.newBuilder()
                .hintsPartialSignature(HintsPartialSignatureTransactionBody.newBuilder()
                        .constructionId(ACTIVE_CONSTRUCTION_ID)
                        .message(MESSAGE)
                        .partialSignature(signature))
                .build();
    }

    private record ActiveScheme(
            CRSState crsState,
            HintsConstruction construction,
            Bytes privateKey,
            Bytes hint,
            AggregationAndVerificationKeys keys,
            Bytes signature) {}
}
