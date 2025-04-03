// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi;

import static com.hedera.hapi.node.base.ResponseCodeEnum.DUPLICATE_TRANSACTION;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.signatures.SignatureVerifier;
import com.hedera.node.app.spi.throttle.Throttle;
import com.swirlds.common.crypto.Signature;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.lifecycle.EntityIdFactory;
import com.swirlds.state.lifecycle.Service;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Gives context to {@link Service} implementations on how the application workflows will do
 * shared functions like verifying signatures or computing the current instant.
 */
public interface AppContext {
    Logger log = LogManager.getLogger(AppContext.class);

    /**
     * The {@link Gossip} interface is used to submit transactions to the network.
     */
    interface Gossip {
        int NANOS_TO_SKIP_ON_DUPLICATE = 13;
        String DUPLICATE_TRANSACTION_REASON = "" + DUPLICATE_TRANSACTION;

        /**
         * A {@link Gossip} that throws an exception indicating it should never have been used; for example,
         * if the client code was running in a standalone mode.
         */
        Gossip UNAVAILABLE_GOSSIP = new Gossip() {
            @Override
            public void submit(@NonNull final TransactionBody body) {
                throw new IllegalStateException("Gossip is not available!");
            }

            @Override
            public Signature sign(final byte[] ledgerId) {
                throw new IllegalStateException("Gossip is not available!");
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };

        /**
         * Uses the given executor to schedule a retryable gossip submission for a transaction customized by the
         * provided spec with the given valid duration, node account id, and valid start times no later than the
         * given estimate of current consensus time.
         * <p>
         * Returns a future that completes normally if the transaction is eventually submitted, or exceptionally
         * if the transaction cannot be submitted after the given number of attempts. The message of the exception
         * will be the reason for the final failure.
         *
         * @param selfId the id of the node submitting the transaction
         * @param consensusNow an estimate of current consensus time
         * @param validDuration the duration for which the transaction is valid
         * @param spec a consumer that will populate the transaction body
         * @param executor the executor to use for submitting the transaction
         * @param timesToTry the number of times to try submitting the transaction
         * @param distinctTxnIdsPerTry the number of distinct transaction ids to try per attempt
         * @param retryDelay the delay between retries
         * @param onFailure the consumer to call when a submission attempt fails
         * @return a future that will complete when the transaction is submitted
         */
        default CompletableFuture<Void> submitFuture(
                @NonNull final AccountID selfId,
                @NonNull final Instant consensusNow,
                @NonNull final Duration validDuration,
                @NonNull final Consumer<TransactionBody.Builder> spec,
                @NonNull final Executor executor,
                final int timesToTry,
                final int distinctTxnIdsPerTry,
                @NonNull final Duration retryDelay,
                @NonNull final BiConsumer<TransactionBody, String> onFailure) {
            final var attemptsLeft = new AtomicInteger(timesToTry);
            final var validStartTime = new AtomicReference<>(consensusNow);
            final var txnIdValidDuration = new com.hedera.hapi.node.base.Duration(validDuration.toSeconds());
            return CompletableFuture.runAsync(
                    () -> {
                        var fatalFailure = false;
                        var failureReason = "<N/A>";
                        TransactionBody body;
                        do {
                            int txnIdsLeft = distinctTxnIdsPerTry;
                            do {
                                final var builder = TransactionBody.newBuilder()
                                        .nodeAccountID(selfId)
                                        .transactionValidDuration(txnIdValidDuration)
                                        .transactionID(
                                                new TransactionID(asTimestamp(validStartTime.get()), selfId, false, 0));
                                spec.accept(builder);
                                body = builder.build();
                                try {
                                    submit(body);
                                    return;
                                } catch (IllegalArgumentException iae) {
                                    failureReason = iae.getMessage();
                                    if (DUPLICATE_TRANSACTION_REASON.equals(failureReason)) {
                                        validStartTime.set(validStartTime.get().plusNanos(NANOS_TO_SKIP_ON_DUPLICATE));
                                        log.info(
                                                "Retrying {} after duplicate transaction",
                                                body.data().kind());
                                    } else {
                                        fatalFailure = true;
                                        break;
                                    }
                                } catch (IllegalStateException ise) {
                                    failureReason = ise.getMessage();
                                    // There is no point to retry immediately except on a duplicate id
                                    break;
                                }
                            } while (txnIdsLeft-- > 1);
                            onFailure.accept(body, failureReason);
                            if (!fatalFailure) {
                                log.info("Retrying {} after {}", body.data().kind(), failureReason);
                                try {
                                    MILLISECONDS.sleep(retryDelay.toMillis());
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException("Interrupted while waiting to retry " + body, e);
                                }
                            }
                        } while (!fatalFailure && attemptsLeft.decrementAndGet() > 0);
                        throw new IllegalStateException(failureReason);
                    },
                    executor);
        }

        /**
         * Attempts to submit the given transaction to the network.
         *
         * @param body the transaction to submit
         * @throws IllegalStateException    if the network is not active; the client should retry later
         * @throws IllegalArgumentException if body is invalid; so the client can retry immediately with a
         *                                  different transaction id if the exception's message is {@link ResponseCodeEnum#DUPLICATE_TRANSACTION}
         */
        void submit(@NonNull TransactionBody body);

        /**
         * Signs the given bytes with the node's RSA key and returns the signature.
         *
         * @param bytes the bytes to sign
         * @return the signature
         */
        Signature sign(byte[] bytes);

        /**
         * Whether network gossip is currently active.
         */
        boolean isAvailable();
    }

    /**
     * The source of the current instant.
     *
     * @return the instant source
     */
    InstantSource instantSource();

    /**
     * The signature verifier the application workflows will use.
     *
     * @return the signature verifier
     */
    SignatureVerifier signatureVerifier();

    /**
     * The {@link Gossip} can be used to submit transactions to the network when it is active.
     *
     * @return the gossip interface
     */
    Gossip gossip();

    /**
     * The active configuration of the application.
     * @return the configuration
     */
    Supplier<Configuration> configSupplier();

    /**
     * The supplier of the self node info.
     * @return the supplier
     */
    Supplier<NodeInfo> selfNodeInfoSupplier();

    /**
     * The supplier of (platform) metrics
     * @return the supplier
     */
    Supplier<Metrics> metricsSupplier();

    /**
     * The application's strategy for creating {@link Throttle} instances.
     * @return the throttle factory
     */
    Throttle.Factory throttleFactory();

    /**
     * Supplier of the application's strategy for charging fees.
     * @return the fee charging strategy
     */
    Supplier<FeeCharging> feeChargingSupplier();

    /**
     * The application's strategy for creating entity ids.
     * @return the entity id factory
     */
    EntityIdFactory idFactory();
}
