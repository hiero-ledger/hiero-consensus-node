// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.PLATFORM_TRANSACTION_NOT_CREATED;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.UncheckedSubmitBody;
import com.hedera.node.app.fixtures.AppTestBase;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.state.DeduplicationCache;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.metrics.SpeedometerMetric;
import com.swirlds.metrics.api.Metrics;
import java.time.Instant;
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
            assertThatThrownBy(() -> submissionManager.submit(null, bytes)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> submissionManager.submit(txBody, null)).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Submission of the transaction to the platform is a success")
        void submittingToPlatformSucceeds() throws PreCheckException {
            // Given a platform that will succeed in taking bytes
            when(transactionPool.submitApplicationTransaction(any())).thenReturn(true);

            // When we submit bytes
            submissionManager.submit(txBody, bytes);

            // Then the platform actually receives the bytes
            verify(transactionPool).submitApplicationTransaction(bytes);
            // And the metrics keeping track of errors submitting are NOT touched
            verify(platformTxnRejections, never()).cycle();
            // And the deduplication cache is updated
            verify(deduplicationCache).add(txBody.transactionIDOrThrow());
        }

        @Test
        @DisplayName("If the platform fails to onConsensusRound the bytes, a PreCheckException is thrown")
        void testSubmittingToPlatformFails() {
            // Given a platform that will **fail** in taking bytes
            when(transactionPool.submitApplicationTransaction(any())).thenReturn(false);

            // When we submit bytes, then we fail by exception
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes))
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
            when(transactionPool.submitApplicationTransaction(any())).thenReturn(true);
            when(deduplicationCache.contains(txBody.transactionIDOrThrow()))
                    .thenReturn(false)
                    .thenReturn(true);

            // When we submit a duplicate transaction twice in close succession, then the second one fails
            // with a DUPLICATE_TRANSACTION error
            submissionManager.submit(txBody, bytes);
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes))
                    .isInstanceOf(PreCheckException.class)
                    .extracting(t -> ((PreCheckException) t).responseCode())
                    .isEqualTo(DUPLICATE_TRANSACTION);
            // And the deduplication cache is updated just once
            verify(deduplicationCache).add(txBody.transactionIDOrThrow());
        }
    }

    @Nested
    @DisplayName("Tests for unchecked transaction submission")
    class UncheckedSubmitTest extends AppTestBase {
        /** Mocked Metrics allowing us to see if the speedometer has been modified */
        @Mock
        private Metrics mockedMetrics;
        /** The speedometer metric used by the submission manager */
        @Mock
        private SpeedometerMetric platformTxnRejections;
        /** The submission manager instance */
        private SubmissionManager submissionManager;
        /** Representative of the raw transaction bytes */
        private Bytes bytes;
        /** The TransactionBody of the transaction we are submitting */
        private TransactionBody txBody;
        /** Representative of the unchecked transaction bytes */
        private Bytes uncheckedBytes;

        @BeforeEach
        void setup() {
            config = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("hedera.profiles.active", "TEST")
                            .withValue("ledger.id", "0x03")
                            .getOrCreateConfig(),
                    1);
            when(mockedMetrics.getOrCreate(any())).thenReturn(platformTxnRejections);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            bytes = randomBytes(25);

            final var uncheckedTx = simpleCryptoTransfer();
            uncheckedBytes = Bytes.wrap(asByteArray(uncheckedTx));
            txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(Instant.now()))
                            .build())
                    .uncheckedSubmit(UncheckedSubmitBody.newBuilder()
                            .transactionBytes(uncheckedBytes)
                            .build())
                    .build();
        }

        @Test
        @DisplayName("An unchecked transaction not in PROD mode can be submitted")
        void testSuccessWithUncheckedSubmit() throws PreCheckException {
            // Given a platform that will succeed in taking the *unchecked* bytes
            when(transactionPool.submitApplicationTransaction(uncheckedBytes)).thenReturn(true);

            // When we submit an unchecked transaction, and separate bytes
            submissionManager.submit(txBody, bytes);

            // Then the platform actually sees the unchecked bytes
            verify(transactionPool).submitApplicationTransaction(uncheckedBytes);
            // And the metrics keeping track of errors submitting are NOT touched
            verify(platformTxnRejections, never()).cycle();
            // And the deduplication cache is updated
            verify(deduplicationCache).add(any());
        }

        @Test
        @DisplayName("An unchecked transaction in PROD mode WILL FAIL")
        void testUncheckedSubmitInProdFails() {
            // Given we are in PROD mode
            config = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("hedera.profiles.active", "PROD")
                            .withValue("ledger.id", "0x03")
                            .getOrCreateConfig(),
                    1);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            // When we submit an unchecked transaction, and separate bytes, then the
            // submission FAILS because we are in PROD mode
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", PLATFORM_TRANSACTION_NOT_CREATED);

            // Then the platform NEVER sees the unchecked bytes
            verify(transactionPool, never()).submitApplicationTransaction(uncheckedBytes);
            // We never attempted to submit this tx to the platform, so we don't increase the metric
            verify(platformTxnRejections, never()).cycle();
            // And the deduplication cache is not updated
            verify(deduplicationCache, never()).add(any());
        }

        @Test
        @DisplayName("An unchecked transaction on MainNet WILL FAIL")
        void testUncheckedSubmitOnMainNetFails() {
            // Given we are in PROD mode
            config = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("hedera.profiles.active", "TEST")
                            .withValue("ledger.id", "0x00")
                            .getOrCreateConfig(),
                    1);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            // When we submit an unchecked transaction, and separate bytes, then the
            // submission FAILS because we are in PROD mode
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", PLATFORM_TRANSACTION_NOT_CREATED);

            // Then the platform NEVER sees the unchecked bytes
            verify(transactionPool, never()).submitApplicationTransaction(uncheckedBytes);
            // We never attempted to submit this tx to the platform, so we don't increase the metric
            verify(platformTxnRejections, never()).cycle();
            // And the deduplication cache is not updated
            verify(deduplicationCache, never()).add(any());
        }

        @Test
        @DisplayName("An unchecked transaction on TestNet WILL FAIL")
        void testUncheckedSubmitOnTestNetFails() {
            // Given we are in PROD mode
            config = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("hedera.profiles.active", "TEST")
                            .withValue("ledger.id", "0x01")
                            .getOrCreateConfig(),
                    1);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            // When we submit an unchecked transaction, and separate bytes, then the
            // submission FAILS because we are in PROD mode
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", PLATFORM_TRANSACTION_NOT_CREATED);

            // Then the platform NEVER sees the unchecked bytes
            verify(transactionPool, never()).submitApplicationTransaction(uncheckedBytes);
            // We never attempted to submit this tx to the platform, so we don't increase the metric
            verify(platformTxnRejections, never()).cycle();
            // And the deduplication cache is not updated
            verify(deduplicationCache, never()).add(any());
        }

        @Test
        @DisplayName("An unchecked transaction on PreviewNet WILL FAIL")
        void testUncheckedSubmitOnPreviewNetFails() {
            // Given we are in PROD mode
            config = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("hedera.profiles.active", "TEST")
                            .withValue("ledger.id", "0x02")
                            .getOrCreateConfig(),
                    1);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);

            // When we submit an unchecked transaction, and separate bytes, then the
            // submission FAILS because we are in PROD mode
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", PLATFORM_TRANSACTION_NOT_CREATED);

            // Then the platform NEVER sees the unchecked bytes
            verify(transactionPool, never()).submitApplicationTransaction(uncheckedBytes);
            // We never attempted to submit this tx to the platform, so we don't increase the metric
            verify(platformTxnRejections, never()).cycle();
            // And the deduplication cache is not updated
            verify(deduplicationCache, never()).add(any());
        }

        // TEST: If the unchecked submit is bogus bytes, or fails the onset check in some way, then
        // it must be rejected
        @Test
        @DisplayName("Send bogus bytes as an unchecked transaction and verify it fails with a PreCheckException")
        void testBogusBytes() {
            // Given we are in TEST mode and have a transaction with bogus bytes
            config = () -> new VersionedConfigImpl(
                    HederaTestConfigBuilder.create()
                            .withValue("hedera.profiles.active", "TEST")
                            .getOrCreateConfig(),
                    1);
            submissionManager = new SubmissionManager(transactionPool, deduplicationCache, config, mockedMetrics);
            txBody = TransactionBody.newBuilder()
                    .transactionID(TransactionID.newBuilder()
                            .transactionValidStart(asTimestamp(Instant.now()))
                            .build())
                    .uncheckedSubmit(UncheckedSubmitBody.newBuilder()
                            .transactionBytes(randomBytes(25))
                            .build())
                    .build();

            // When we submit an unchecked transaction with bogus bytes, and separate bytes, then the
            // submission FAILS because of the bogus bytes
            assertThatThrownBy(() -> submissionManager.submit(txBody, bytes))
                    .isInstanceOf(PreCheckException.class)
                    .hasFieldOrPropertyWithValue("responseCode", PLATFORM_TRANSACTION_NOT_CREATED);

            // Then the platform NEVER sees the unchecked bytes
            verify(transactionPool, never()).submitApplicationTransaction(uncheckedBytes);
            // And the deduplication cache is not updated
            verify(deduplicationCache, never()).add(any());
        }
    }
}
