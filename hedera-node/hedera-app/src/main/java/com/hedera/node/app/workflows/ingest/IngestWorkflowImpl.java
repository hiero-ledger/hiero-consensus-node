// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.ingest;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.node.app.quiescence.QuiescenceUtils.isRelevantTransaction;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.transaction.TransactionResponse;
import com.hedera.node.app.quiescence.QuiescenceController;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.InsufficientBalanceException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.throttle.ThrottleUsage;
import com.hedera.node.app.workflows.prehandle.PreHandleResult;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.QuiescenceConfig;
import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.transaction.Transaction;

/** Implementation of {@link IngestWorkflow} */
@Singleton
public final class IngestWorkflowImpl implements IngestWorkflow {

    private static final Logger logger = LogManager.getLogger(IngestWorkflowImpl.class);

    private final Supplier<AutoCloseableWrapper<State>> stateAccessor;
    private final IngestChecker ingestChecker;
    private final SubmissionManager submissionManager;
    private final ConfigProvider configProvider;
    private final boolean quiescenceEnabled;
    private final AtomicInteger preFlightCount = new AtomicInteger();
    private final AtomicInteger inFlightCount = new AtomicInteger();

    /**
     * Constructor of {@code IngestWorkflowImpl}
     *
     * @param stateAccessor a {@link Supplier} that provides the latest immutable state
     * @param ingestChecker the {@link IngestChecker} with specific checks of an ingest-workflow
     * @param submissionManager the {@link SubmissionManager} to submit transactions to the platform
     * @param configProvider the {@link ConfigProvider} to provide the configuration
     * @throws NullPointerException if one of the arguments is {@code null}
     */
    @Inject
    public IngestWorkflowImpl(
            @NonNull final Supplier<AutoCloseableWrapper<State>> stateAccessor,
            @NonNull final IngestChecker ingestChecker,
            @NonNull final SubmissionManager submissionManager,
            @NonNull final ConfigProvider configProvider) {
        this.stateAccessor = requireNonNull(stateAccessor);
        this.ingestChecker = requireNonNull(ingestChecker);
        this.submissionManager = requireNonNull(submissionManager);
        this.configProvider = requireNonNull(configProvider);
        this.quiescenceEnabled = configProvider
                .getConfiguration()
                .getConfigData(QuiescenceConfig.class)
                .enabled();
    }

    @Override
    public int estimateTxPipelineCount() {
        return quiescenceEnabled ? (preFlightCount.get() + inFlightCount.get()) : 0;
    }

    /**
     * Called with transactions that landed in purview of the {@link QuiescenceController}, either as a result of
     * pre-handle or stale events, <b>if</b> these transactions were in an event created by this node.
     * <p>
     * Note that every user transaction submitted via ingest will certainly be relevant to quiescence, but the
     * iterator might include other self-created transactions that are not relevant to quiescence.
     * @param iter iterator of self-created transactions that landed
     */
    public void countLanded(@NonNull final Iterator<Transaction> iter) {
        if (!quiescenceEnabled) {
            return;
        }
        while (iter.hasNext()) {
            final var tx = iter.next();
            if (tx.getMetadata() instanceof PreHandleResult preHandleResult) {
                final var txInfo = preHandleResult.txInfo();
                if (txInfo != null && isRelevantTransaction(txInfo.txBody())) {
                    inFlightCount.accumulateAndGet(-1, (prev, next) -> Math.max(0, prev + next));
                }
            }
        }
    }

    @Override
    public void submitTransaction(@NonNull final Bytes requestBuffer, @NonNull final BufferedData responseBuffer) {
        requireNonNull(requestBuffer);
        requireNonNull(responseBuffer);

        try {
            if (quiescenceEnabled) {
                preFlightCount.incrementAndGet();
            }
            ResponseCodeEnum result = OK;
            long estimatedFee = 0L;

            // Track any throttle capacity used so we can reclaim it if we don't actually submit to consensus
            final var checkerResult = new IngestChecker.Result();
            // Grab (and reference count) the state, so we have a consistent view of things
            try (final var wrappedState = stateAccessor.get()) {
                // 0. Node state pre-checks
                ingestChecker.verifyReadyForTransactions();

                // 1.-6. Parse and check the transaction
                final var state = wrappedState.get();
                final var configuration = configProvider.getConfiguration();
                ingestChecker.runAllChecks(state, requestBuffer, configuration, checkerResult);

                // 7. Submit to platform
                final var txInfo = checkerResult.txnInfoOrThrow();
                submissionManager.submit(txInfo.txBody(), txInfo.serializedSignedTxOrThrow());
                if (quiescenceEnabled) {
                    inFlightCount.incrementAndGet();
                }
            } catch (final InsufficientBalanceException e) {
                estimatedFee = e.getEstimatedFee();
                result = e.responseCode();
            } catch (final PreCheckException e) {
                logger.debug("Transaction failed pre-check", e);
                result = e.responseCode();
            } catch (final HandleException e) {
                logger.debug("Transaction failed pre-check", e);
                // Conceptually, this should never happen, because we should use PreCheckException only during
                // pre-checks
                // But we catch it here to play it safe
                result = e.getStatus();
            } catch (final Exception e) {
                logger.error("Possibly CATASTROPHIC failure while running the ingest workflow", e);
                result = ResponseCodeEnum.FAIL_INVALID;
            }
            // Reclaim any used throttle capacity if we failed (i.e., did not submit the transaction to consensus)
            if (result != OK) {
                checkerResult.throttleUsages().forEach(ThrottleUsage::reclaimCapacity);
            }

            // 8. Return PreCheck code and estimated fee
            final var transactionResponse = TransactionResponse.newBuilder()
                    .nodeTransactionPrecheckCode(result)
                    .cost(estimatedFee)
                    .build();

            try {
                TransactionResponse.PROTOBUF.write(transactionResponse, responseBuffer);
            } catch (IOException ex) {
                // It may be that the response couldn't be written because the response buffer was
                // too small, which would be an internal server error.
                throw new UncheckedIOException("Failed to write bytes to response buffer", ex);
            }
        } finally {
            if (quiescenceEnabled) {
                preFlightCount.decrementAndGet();
            }
        }
    }
}
