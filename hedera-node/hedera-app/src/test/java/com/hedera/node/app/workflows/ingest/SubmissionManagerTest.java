// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatNoException;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.SignedTransaction;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.util.AtomicBatchTransactionBody;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.metrics.SpeedometerMetric;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.transaction.TransactionLimits;
import org.hiero.consensus.transaction.TransactionPoolNexus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class SubmissionManagerTest extends AppTestBase {
    /** A mocked transaction pool for accepting or rejecting submission of transaction bytes */
    @Mock
    private TransactionPoolNexus transactionPool;
    /** Mocked global properties to verify default transaction duration */
    @Mock
    private DeduplicationCache deduplicationCache;
    /** Configuration */
    private ConfigProvider config;

    @BeforeEach
    void setUp() {
        config = () -> new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);
    }

    @Test
    @DisplayName("Null cannot be provided as any of the constructor args")
    @SuppressWarnings("ConstantConditions")
    void testConstructorWithIllegalParameters() {
        assertThatThrownBy(() -> new SubmissionManager(null, deduplicationCache, config, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubmissionManager(transactionPool, null, config, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubmissionManager(transactionPool, deduplicationCache, null, metrics))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SubmissionManager(transactionPool, deduplicationCache, config, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    @DisplayName("Tests for normal transaction submission")
    class SubmitTest extends AppTestBase {
        /**
         * Mocked Metrics allowing us to see if the speedometer has been modified
         */
        @Mock
        private Metrics mockedMetrics;
        /**
         * The speedometer metric used by the submission manager
         */
        @Mock
        private SpeedometerMetric platformTxnRejections;
        /**
         * The submission manager instance
         */
        private SubmissionManager submissionManager;
        /**
         * Representative of the raw transaction bytes
         */
        private Bytes bytes;
        /**
         * The TransactionBody of the transaction we are submitting
         */
        private TransactionBody txBody;

        @BeforeEach
        void setup() {
            bytes = randomBytes(25);
            when(mockedMetrics.getOrCreate(any())).thenReturn(platformTxnRejections);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);
            txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(Instant.now()))
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Null cannot be provided as any of the 'submit' args")
        @SuppressWarnings("ConstantConditions")
        void testSubmitWithIllegalParameters() {
            assertThatThrownBy(() -> submissionManager.submit(null, bytes, false))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> submissionManager.submit(txBody, null, false))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Submission of the transaction to the platform is a success")
        void submittingToPlatformSucceeds() throws PreCheckException {
            // Given a platform that will succeed in taking bytes
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);

            // When we submit bytes
            submissionManager.submit(txBody, bytes, false);

            // Then the platform actually receives the bytes, with no drop time since the body has no valid duration
            verify(transactionPool).submitApplicationTransaction(bytes, null);
            // And the metrics keeping track of errors submitting are NOT touched
            verify(platformTxnRejections, never()).cycle();
            // And the deduplication cache is updated
            verify(deduplicationCache).add(txBody.transactionIDOrThrow());
        }

        @Test
        @DisplayName("If the platform fails to onConsensusRound the bytes, a PreCheckException is thrown")
        void testSubmittingToPlatformFails() {
            // Given a platform that will **fail** in taking bytes
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(false);

            // When we submit bytes, then we fail by exception
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes, false))
                    .isInstanceOf(PreCheckException.class)
                    .extracting(t -> ((PreCheckException) t).responseCode())
                    .isEqualTo(PLATFORM_TRANSACTION_NOT_CREATED);
            // And the error metrics HAVE been updated
            verify(platformTxnRejections).cycle();
            // And the deduplication cache is NOT called
            verify(deduplicationCache, never()).add(txBody.transactionIDOrThrow());
        }

        @Test
        @DisplayName("Submitting the same transaction twice in close succession rejects the duplicate")
        void testSubmittingDuplicateTransactionsCloseTogether() throws PreCheckException {
            // Given a platform that will succeed in taking bytes
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);
            when(deduplicationCache.contains(txBody.transactionIDOrThrow()))
                    .thenReturn(false)
                    .thenReturn(true);

            // When we submit a duplicate transaction twice in close succession, then the second one fails
            // with a DUPLICATE_TRANSACTION error
            submissionManager.submit(txBody, bytes, false);
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes, false))
                    .isInstanceOf(PreCheckException.class)
                    .extracting(t -> ((PreCheckException) t).responseCode())
                    .isEqualTo(DUPLICATE_TRANSACTION);
            // And the deduplication cache is updated just once
            verify(deduplicationCache).add(txBody.transactionIDOrThrow());
        }
    }

    @Nested
    @DisplayName("Tests for atomic batch transaction submission")
    class AtomicBatchSubmitTest extends AppTestBase {
        @Mock
        private Metrics mockedMetrics;

        @Mock
        private SpeedometerMetric platformTxnRejections;

        private SubmissionManager submissionManager;
        private Bytes mainBytes;
        private TransactionBody txBodyWithBatch;
        private List<Bytes> batchTransactions;

        @BeforeEach
        void setup() {
            when(mockedMetrics.getOrCreate(any())).thenReturn(platformTxnRejections);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            // Create main transaction bytes
            mainBytes = randomBytes(25);

            // Create batch transactions
            batchTransactions = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                // Create a signed transaction with a valid body inside
                var innerTxBody = TransactionBody.newBuilder()
                        .transactionID(TransactionID.newBuilder()
                                .transactionValidStart(asTimestamp(Instant.now().plusSeconds(i)))
                                .build())
                        .build();

                var signedTx = SignedTransaction.newBuilder()
                        .bodyBytes(asBytes(TransactionBody.PROTOBUF, innerTxBody))
                        .build();

                batchTransactions.add(Bytes.wrap(asByteArray(signedTx)));
            }

            // Create main transaction body with batch
            txBodyWithBatch = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(Instant.now()))
                            .build())
                    .atomicBatch(AtomicBatchTransactionBody.newBuilder()
                            .transactions(batchTransactions)
                            .build())
                    .build();
        }

        @Test
        @DisplayName("Successfully submits atomic batch with valid inner transactions")
        void testAtomicBatchSuccess() throws PreCheckException {
            // Given a platform that will succeed in taking bytes
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);
            when(deduplicationCache.contains(any())).thenReturn(false);

            // When we submit a transaction with an atomic batch
            submissionManager.submit(txBodyWithBatch, mainBytes, false);

            // Then the platform receives the main bytes
            verify(transactionPool).submitApplicationTransaction(mainBytes, null);

            // And the deduplication cache is updated for the main transaction
            verify(deduplicationCache).add(txBodyWithBatch.transactionIDOrThrow());

            // And for each transaction in the batch (1 main + 2 inner = 3 total)
            verify(deduplicationCache, times(3)).add(any());
        }

        @Test
        @DisplayName("Handles parse exception from invalid batch transaction")
        void testAtomicBatchWithParseException() throws Exception {
            // Given a transaction not yet submitted
            when(deduplicationCache.contains(any())).thenReturn(false);

            // Create a batch with an invalid transaction
            List<Bytes> invalidBatch = new ArrayList<>(batchTransactions);
            invalidBatch.add(randomBytes(10)); // Add invalid bytes that will cause ParseException

            // Create transaction body with invalid batch
            TransactionBody txBodyWithInvalidBatch = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(Instant.now()))
                            .build())
                    .atomicBatch(AtomicBatchTransactionBody.newBuilder()
                            .transactions(invalidBatch)
                            .build())
                    .build();

            // When we submit a transaction with an atomic batch containing invalid data
            assertThatThrownBy(() -> submissionManager.submit(txBodyWithInvalidBatch, mainBytes, false))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", INVALID_TRANSACTION);

            // Then the inner transactions are parsed before submitting, so the platform never receives the bytes
            verify(transactionPool, never()).submitApplicationTransaction(any(), any());

            // And the deduplication cache is not updated
            verify(deduplicationCache, never()).add(any());
        }

        @Test
        @DisplayName("Handles empty atomic batch correctly")
        void testEmptyAtomicBatch() throws PreCheckException {
            // Create transaction body with empty batch
            TransactionBody txBodyWithEmptyBatch = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(Instant.now()))
                            .build())
                    .atomicBatch(AtomicBatchTransactionBody.newBuilder()
                            .transactions(Collections.emptyList())
                            .build())
                    .build();

            // Given a platform that will succeed in taking bytes
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);
            when(deduplicationCache.contains(any())).thenReturn(false);

            // When we submit a transaction with an empty atomic batch
            submissionManager.submit(txBodyWithEmptyBatch, mainBytes, false);

            // Then the platform receives the main bytes
            verify(transactionPool).submitApplicationTransaction(mainBytes, null);

            // And the deduplication cache is updated for the main transaction only
            verify(deduplicationCache).add(txBodyWithEmptyBatch.transactionIDOrThrow());
            verify(deduplicationCache, times(1)).add(any());
        }
    }

    /**
     * End-to-end tests using a real {@link TransactionPoolNexus} (not mocked) to prove
     * that platform unhealthiness causes {@code PLATFORM_TRANSACTION_NOT_CREATED} through
     * the full SubmissionManager -> TransactionPoolNexus chain, and that increasing the
     * unhealthy duration threshold prevents the rejection.
     */
    @Nested
    @DisplayName("End-to-end: unhealthy duration -> pool rejection -> PLATFORM_TRANSACTION_NOT_CREATED")
    class UnhealthyDurationEndToEndTest extends AppTestBase {
        @Mock
        private Metrics mockedMetrics;

        @Mock
        private SpeedometerMetric platformTxnRejections;

        @Mock
        private DeduplicationCache deduplicationCache;

        private static final TransactionLimits TX_LIMITS = new TransactionLimits(6_144, 245_760);
        private static final int TX_QUEUE_SIZE = 100_000;

        private TransactionBody txBody;
        private Bytes txBytes;

        @BeforeEach
        void setup() {
            when(mockedMetrics.getOrCreate(any())).thenReturn(platformTxnRejections);
            txBytes = randomBytes(25);
            txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(Instant.now()))
                            .build())
                    .build();
        }

        @Test
        @DisplayName("With default 1s threshold, unhealthy duration >= 1s causes PLATFORM_TRANSACTION_NOT_CREATED")
        void unhealthyPlatformCausesPlatformTransactionNotCreated() {
            // Given a real TransactionPoolNexus with the default 1-second threshold
            final var realPool = new TransactionPoolNexus(
                    TX_LIMITS,
                    TX_QUEUE_SIZE,
                    TransactionPoolNexus.DEFAULT_MAXIMUM_PERMISSIBLE_UNHEALTHY_DURATION,
                    new NoOpMetrics(),
                    new FakeTime());
            realPool.updatePlatformStatus(PlatformStatus.ACTIVE);

            final var submissionManager = new SubmissionManager(realPool, deduplicationCache, config, mockedMetrics);

            // When the platform has been unhealthy for 2 seconds (exceeding 1s threshold)
            realPool.reportUnhealthyDuration(Duration.ofSeconds(2));

            // Then submitting a transaction throws PLATFORM_TRANSACTION_NOT_CREATED
            assertThatThrownBy(() -> submissionManager.submit(txBody, txBytes, false))
                    .isInstanceOf(PreCheckException.class)
                    .extracting(t -> ((PreCheckException) t).responseCode())
                    .isEqualTo(PLATFORM_TRANSACTION_NOT_CREATED);
        }

        @Test
        @DisplayName("With increased 5s threshold, 2s unhealthy duration is tolerated")
        void increasedThresholdToleratesTransientUnhealthiness() throws PreCheckException {
            // Given a real TransactionPoolNexus with a 5-second threshold (CI override)
            final var tolerantPool = new TransactionPoolNexus(
                    TX_LIMITS, TX_QUEUE_SIZE, Duration.ofSeconds(5), new NoOpMetrics(), new FakeTime());
            tolerantPool.updatePlatformStatus(PlatformStatus.ACTIVE);

            final var submissionManager =
                    new SubmissionManager(tolerantPool, deduplicationCache, config, mockedMetrics);

            // When the platform has been unhealthy for 2 seconds (would fail with 1s default)
            tolerantPool.reportUnhealthyDuration(Duration.ofSeconds(2));

            // Then submitting a transaction succeeds — the increased threshold prevents rejection
            assertThatNoException().isThrownBy(() -> submissionManager.submit(txBody, txBytes, false));

            // And the deduplication cache is updated, confirming the transaction was accepted
            verify(deduplicationCache).add(txBody.transactionIDOrThrow());
        }
    }

    @Nested
    @DisplayName("Tests for the time after which the pool drops a transaction")
    class DropTimeTest extends AppTestBase {
        private static final Instant VALID_START = Instant.parse("2026-11-10T22:19:06.690Z");

        @Mock
        private Metrics mockedMetrics;

        @Mock
        private SpeedometerMetric platformTxnRejections;

        private final Bytes bytes = Bytes.wrap(new byte[] {1, 2, 3});

        @BeforeEach
        void setup() {
            when(mockedMetrics.getOrCreate(any())).thenReturn(platformTxnRejections);
        }

        @Test
        @DisplayName("A transaction is dropped once past its expiry plus the default 30s margin")
        void dropTimeIsExpiryPlusDefaultMargin() throws PreCheckException {
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);
            final var submissionManager =
                    new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            submissionManager.submit(bodyValidFor(VALID_START, 120), bytes, false);

            verify(transactionPool).submitApplicationTransaction(bytes, VALID_START.plusSeconds(150));
        }

        @Test
        @DisplayName("The margin past expiry is configurable")
        void dropTimeUsesConfiguredMargin() throws PreCheckException {
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);
            final var submissionManager = new SubmissionManager(
                    transactionPool,
                    deduplicationCache,
                    configWith("hedera.transaction.expiredPoolDropMarginSecs", 5),
                    mockedMetrics);

            submissionManager.submit(bodyValidFor(VALID_START, 120), bytes, false);

            verify(transactionPool).submitApplicationTransaction(bytes, VALID_START.plusSeconds(125));
        }

        @Test
        @DisplayName("A transaction gets no drop time when dropping is disabled")
        void noDropTimeWhenDisabled() throws PreCheckException {
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);
            final var submissionManager = new SubmissionManager(
                    transactionPool,
                    deduplicationCache,
                    configWith("hedera.transaction.dropExpiredFromPool", false),
                    mockedMetrics);

            submissionManager.submit(bodyValidFor(VALID_START, 120), bytes, false);

            verify(transactionPool).submitApplicationTransaction(bytes, null);
        }

        @Test
        @DisplayName("An atomic batch is dropped by the earliest expiry of its outer and inner transactions")
        void batchUsesEarliestExpiry() throws PreCheckException {
            when(transactionPool.submitApplicationTransaction(any(), any())).thenReturn(true);
            final var submissionManager =
                    new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);
            final var innerWithoutDuration = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(VALID_START.minusSeconds(100)))
                            .build())
                    .build();
            final var batch = bodyValidFor(VALID_START, 120)
                    .copyBuilder()
                    .atomicBatch(AtomicBatchTransactionBody.newBuilder()
                            .transactions(List.of(
                                    // Expires at VALID_START + 70, the earliest
                                    signed(bodyValidFor(VALID_START.minusSeconds(50), 120)),
                                    signed(bodyValidFor(VALID_START, 180)),
                                    // Says nothing about its expiry, so it is ignored
                                    signed(innerWithoutDuration)))
                            .build())
                    .build();

            submissionManager.submit(batch, bytes, false);

            verify(transactionPool).submitApplicationTransaction(bytes, VALID_START.plusSeconds(100));
            verify(deduplicationCache, times(4)).add(any());
        }

        @Test
        @DisplayName("Priority transactions are submitted without a drop time")
        void priorityTransactionsGetNoDropTime() throws PreCheckException {
            final var submissionManager =
                    new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            submissionManager.submit(bodyValidFor(VALID_START, 120), bytes, true);

            verify(transactionPool).submitPriorityTransaction(bytes);
            verify(transactionPool, never()).submitApplicationTransaction(any(), any());
        }

        @Test
        @DisplayName("A real pool keeps a transaction up to its drop time, then drops it")
        void realPoolDropsTransactionPastItsDropTime() throws PreCheckException {
            final var time = new FakeTime(VALID_START, Duration.ZERO);
            final List<Integer> drops = new ArrayList<>();
            final var pool = new TransactionPoolNexus(
                    new TransactionLimits(6_144, 245_760),
                    100_000,
                    TransactionPoolNexus.DEFAULT_MAXIMUM_PERMISSIBLE_UNHEALTHY_DURATION,
                    new NoOpMetrics(),
                    time,
                    dropped -> drops.add(dropped));
            pool.updatePlatformStatus(PlatformStatus.ACTIVE);
            final var submissionManager = new SubmissionManager(pool, deduplicationCache, config, mockedMetrics);
            final var expired = Bytes.wrap(new byte[] {1});
            final var stillValid = Bytes.wrap(new byte[] {2});
            submissionManager.submit(bodyValidFor(VALID_START, 120), expired, false);
            submissionManager.submit(bodyValidFor(VALID_START.plusSeconds(10), 120), stillValid, false);

            // The first is 5s past its drop time (expiry + 30s), the second 5s before its own
            time.tick(Duration.ofSeconds(155));

            assertEquals(
                    List.of(stillValid),
                    pool.getTransactionsForEvent().stream()
                            .map(TimestampedTransaction::transaction)
                            .toList());
            assertEquals(List.of(1), drops);
        }

        private TransactionBody bodyValidFor(final Instant validStart, final long seconds) {
            return TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(validStart))
                            .build())
                    .transactionValidDuration(com.hedera.hapi.node.base.Duration.newBuilder()
                            .seconds(seconds)
                            .build())
                    .build();
        }

        private Bytes signed(final TransactionBody body) {
            return Bytes.wrap(asByteArray(SignedTransaction.newBuilder()
                    .bodyBytes(asBytes(TransactionBody.PROTOBUF, body))
                    .build()));
        }

        private ConfigProvider configWith(final String name, final Object value) {
            final var configuration =
                    HederaTestConfigBuilder.create().withValue(name, value).getOrCreateConfig();
            return () -> new VersionedConfigImpl(configuration, 1);
        }
    }
}
