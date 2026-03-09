// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.synchronous;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.transaction.TransactionRecord;
import com.hedera.node.app.quiescence.TxPipelineTracker;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.throttle.ThrottleUsage;
import com.hedera.node.app.workflows.ingest.IngestChecker;
import com.hedera.node.app.workflows.ingest.SubmissionManager;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.QuiescenceConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Implementation of {@link SynchronousWorkflow} */
@Singleton
public final class SynchronousWorkflowImpl implements SynchronousWorkflow {

    private static final Logger logger = LogManager.getLogger(SynchronousWorkflowImpl.class);

    private static final long FUTURE_TIMEOUT_SECONDS = 30L;

    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final IngestChecker ingestChecker;
    private final SubmissionManager submissionManager;
    private final TxPipelineTracker txPipelineTracker;
    private final ConfigProvider configProvider;
    private final PendingFutureRegistry pendingFutureRegistry;
    private final boolean quiescenceEnabled;

    @Inject
    public SynchronousWorkflowImpl(
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final IngestChecker ingestChecker,
            @NonNull final SubmissionManager submissionManager,
            @NonNull final TxPipelineTracker txPipelineTracker,
            @NonNull final ConfigProvider configProvider,
            @NonNull final PendingFutureRegistry pendingFutureRegistry) {
        this.stateAccessor = requireNonNull(stateAccessor);
        this.ingestChecker = requireNonNull(ingestChecker);
        this.submissionManager = requireNonNull(submissionManager);
        this.txPipelineTracker = requireNonNull(txPipelineTracker);
        this.configProvider = requireNonNull(configProvider);
        this.pendingFutureRegistry = requireNonNull(pendingFutureRegistry);
        this.quiescenceEnabled = configProvider
                .getConfiguration()
                .getConfigData(QuiescenceConfig.class)
                .enabled();
    }

    @Override
    public void submitAndWait(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        requireNonNull(requestBuffer);
        requireNonNull(responseBuffer);

        try {
            if (quiescenceEnabled) {
                txPipelineTracker.incrementPreFlight();
            }

            // Track any throttle capacity used so we can reclaim it on failure
            final var checkerResult = new IngestChecker.Result();

            try (final var wrappedState = stateAccessor.get()) {
                // 0. Node state pre-checks
                ingestChecker.verifyReadyForTransactions();

                // 1.-6. Parse and validate the transaction
                final var state = wrappedState.get();
                final var configuration = configProvider.getConfiguration();
                ingestChecker.runAllChecks(state, requestBuffer, configuration, checkerResult);

                final var txInfo = checkerResult.txnInfoOrThrow();
                final var txnId = txInfo.transactionID();

                // Register the pending future *before* submitting to avoid a race where
                // HandleWorkflow completes the transaction before we register.
                final var future = pendingFutureRegistry.register(txnId);

                try {
                    // 7. Submit to platform with priority=false
                    submissionManager.submit(txInfo.txBody(), txInfo.serializedSignedTxOrThrow(), false);
                } catch (final Exception submitEx) {
                    // Remove the registered future since submission failed
                    pendingFutureRegistry.fail(txnId, submitEx);
                    throw submitEx;
                }

                if (quiescenceEnabled) {
                    txPipelineTracker.incrementInFlight();
                }

                // 8. Block until HandleWorkflow produces the transaction record
                final com.hedera.node.app.spi.records.RecordSource recordSource;
                try {
                    recordSource = future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (final TimeoutException e) {
                    pendingFutureRegistry.fail(txnId, e);
                    throw new StatusRuntimeException(
                            Status.DEADLINE_EXCEEDED.withDescription("Timed out waiting for transaction record"));
                } catch (final ExecutionException e) {
                    throw new StatusRuntimeException(Status.INTERNAL
                            .withDescription("Catastrophic failure handling transaction")
                            .withCause(e.getCause()));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    pendingFutureRegistry.fail(txnId, e);
                    throw new StatusRuntimeException(
                            Status.INTERNAL.withDescription("Interrupted waiting for transaction record"));
                }

                // 9. Collect the first transaction record and write it to the response buffer
                final var firstRecord = new AtomicReference<TransactionRecord>();
                recordSource.forEachTxnRecord(r -> {
                    if (firstRecord.get() == null) {
                        firstRecord.set(r);
                    }
                });

                final var record = firstRecord.get();
                if (record == null) {
                    throw new StatusRuntimeException(Status.INTERNAL.withDescription("No transaction record produced"));
                }

                try {
                    TransactionRecord.PROTOBUF.write(record, responseBuffer);
                } catch (IOException ex) {
                    throw new UncheckedIOException("Failed to write TransactionRecord to response buffer", ex);
                }

            } catch (final StatusRuntimeException e) {
                throw e;
            } catch (final InsufficientBalanceException e) {
                checkerResult.throttleUsages().forEach(ThrottleUsage::reclaimCapacity);
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(
                        e.responseCode().name()));
            } catch (final PreCheckException e) {
                logger.debug("Transaction failed pre-check in synchronous workflow", e);
                checkerResult.throttleUsages().forEach(ThrottleUsage::reclaimCapacity);
                throw new StatusRuntimeException(Status.FAILED_PRECONDITION.withDescription(
                        e.responseCode().name()));
            } catch (final HandleException e) {
                logger.debug("Transaction failed handle check in synchronous workflow", e);
                checkerResult.throttleUsages().forEach(ThrottleUsage::reclaimCapacity);
                throw new StatusRuntimeException(
                        Status.FAILED_PRECONDITION.withDescription(e.getStatus().name()));
            } catch (final Exception e) {
                logger.error("Possibly CATASTROPHIC failure while running the synchronous workflow", e);
                checkerResult.throttleUsages().forEach(ThrottleUsage::reclaimCapacity);
                throw new StatusRuntimeException(Status.INTERNAL.withDescription("FAIL_INVALID"));
            }
        } finally {
            if (quiescenceEnabled) {
                txPipelineTracker.decrementPreFlight();
            }
        }
    }
}
