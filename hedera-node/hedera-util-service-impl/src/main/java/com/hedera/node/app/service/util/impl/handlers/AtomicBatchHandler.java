// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_LIST_CONTAINS_DUPLICATES;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_LIST_EMPTY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.BATCH_TRANSACTION_IN_BLACKLIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_BATCH_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_NODE_ACCOUNT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_BATCH_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static com.hedera.node.app.spi.workflows.DispatchOptions.atomicBatchDispatch;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.ETHEREUM_NONCE_INCREMENT_CALLBACK;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.INNER_TRANSACTION_BYTES;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.hapi.util.UnknownHederaFunctionality;
import com.hedera.node.app.service.token.api.TokenServiceApi;
import com.hedera.node.app.service.util.impl.cache.InnerTxnCache;
import com.hedera.node.app.service.util.impl.cache.TransactionParser;
import com.hedera.node.app.service.util.impl.records.ReplayableFeeStreamBuilder;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.AtomicBatchConfig;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#ATOMIC_BATCH}.
 */
@Singleton
public class AtomicBatchHandler implements TransactionHandler {
    private final Supplier<FeeCharging> appFeeCharging;
    private final InnerTxnCache innerTxnCache;

    private static final AccountID ATOMIC_BATCH_NODE_ACCOUNT_ID =
            AccountID.newBuilder().accountNum(0).shardNum(0).realmNum(0).build();

    /**
     * Constructs a {@link AtomicBatchHandler}
     */
    @Inject
    public AtomicBatchHandler(
            @NonNull final AppContext appContext, @NonNull final TransactionParser transactionParser) {
        requireNonNull(appContext);
        requireNonNull(transactionParser);
        this.appFeeCharging = appContext.feeChargingSupplier();
        this.innerTxnCache =
                new InnerTxnCache(transactionParser, appContext.configSupplier().get());
    }

    /**
     * Performs checks independent of state or context.
     *
     * @param context the pure checks context
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final List<Bytes> innerTxs = context.body().atomicBatchOrThrow().transactions();
        if (innerTxs.isEmpty()) {
            throw new PreCheckException(BATCH_LIST_EMPTY);
        }

        Set<TransactionID> txIds = new HashSet<>();
        for (final var innerTxBytes : innerTxs) {
            final TransactionBody txBody;
            // use the checked version to throw PreCheckException if we cant parse the transaction
            txBody = innerTxnCache.computeIfAbsent(innerTxBytes);

            // throw if more than one tx has the same transactionID
            validateTruePreCheck(txIds.add(txBody.transactionID()), BATCH_LIST_CONTAINS_DUPLICATES);

            // validates a batch key exists on each inner transaction
            validateTruePreCheck(txBody.hasBatchKey(), MISSING_BATCH_KEY);

            if (!txBody.hasNodeAccountID() || !txBody.nodeAccountIDOrThrow().equals(ATOMIC_BATCH_NODE_ACCOUNT_ID)) {
                throw new PreCheckException(INVALID_NODE_ACCOUNT_ID);
            }
        }
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var atomicBatchTransactionBody = context.body().atomicBatchOrThrow();
        final var config = context.configuration();
        final var atomicBatchConfig = config.getConfigData(AtomicBatchConfig.class);

        final var txns = atomicBatchTransactionBody.transactions();
        // not using a stream below as throwing exception in the middle of a functional pipeline is a terrible idea
        for (final var txnBytes : txns) {
            final var innerTxBody = innerTxnCache.computeIfAbsent(txnBytes);
            validateFalsePreCheck(isNotAllowedFunction(innerTxBody, atomicBatchConfig), BATCH_TRANSACTION_IN_BLACKLIST);
            context.requireKeyOrThrow(innerTxBody.batchKey(), INVALID_BATCH_KEY);
            // the inner prehandle of each inner transaction happens in the prehandle workflow.
        }
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().atomicBatchOrThrow();
        validateTrue(
                context.configuration().getConfigData(AtomicBatchConfig.class).isEnabled(), NOT_SUPPORTED);
        validateFalse(
                op.transactions().size()
                        > context.configuration()
                                .getConfigData(AtomicBatchConfig.class)
                                .maxNumberOfTransactions(),
                BATCH_SIZE_LIMIT_EXCEEDED);

        final var txns = op.transactions();

        // The parsing check is done in the pre-handle workflow,
        // Timebox, and duplication checks are done on dispatch. So, no need to repeat here
        final var recordedFeeCharging = new RecordedFeeCharging(appFeeCharging.get());
        final Map<AccountID, Long> nonceAdjustments = new HashMap<>();
        for (final var txnBytes : txns) {
            // Use the unchecked get because if the transaction is correct, it should be in the cache by now
            final TransactionBody innerTxnBody;
            innerTxnBody = innerTxnCache.computeIfAbsentUnchecked(txnBytes);
            final var payerId = innerTxnBody.transactionIDOrThrow().accountIDOrThrow();
            // Set txn bytes as dispatch metadata. Used to pre-handle inner transaction while dispatching them.
            final var dispatchMetadata = new HandleContext.DispatchMetadata(INNER_TRANSACTION_BYTES, txnBytes);
            final var dispatchOptions = atomicBatchDispatch(
                    payerId, innerTxnBody, ReplayableFeeStreamBuilder.class, recordedFeeCharging, dispatchMetadata);
            if (innerTxnBody.hasEthereumTransaction()) {
                // record nonce updates for Ethereum transactions, so we can replay them after failure
                final BiConsumer<AccountID, Long> nonceUpdateCallback = nonceAdjustments::put;
                dispatchMetadata.putMetadata(ETHEREUM_NONCE_INCREMENT_CALLBACK, nonceUpdateCallback);
            }
            recordedFeeCharging.startRecording();
            final var streamBuilder = context.dispatch(dispatchOptions);
            recordedFeeCharging.finishRecordingTo(streamBuilder);
            if (streamBuilder.status() != SUCCESS) {
                final var tokenServiceApi = context.storeFactory().serviceApi(TokenServiceApi.class);
                throw new HandleException(INNER_TRANSACTION_FAILED, ctx -> {
                    recordedFeeCharging.forEachRecorded((builder, charges) -> {
                        final var adjustments = new TreeMap<AccountID, Long>(ACCOUNT_ID_COMPARATOR);
                        charges.forEach(
                                charge -> charge.replay(ctx, (id, amount) -> adjustments.merge(id, amount, Long::sum)));
                        builder.setReplayedFees(asTransferList(adjustments));
                    });
                    // replay nonce increments for Ethereum transactions
                    nonceAdjustments.forEach(tokenServiceApi::setNonce);
                });
            }
        }
    }

    /**
     * Checks if the given transaction type is not allowed to be included as an inner transaction in an atomic batch.
     *
     * @param transactionBody the transaction body to check
     * @param config          the atomic batch configuration
     * @return true if the transaction type is not allowed, false otherwise
     */
    private boolean isNotAllowedFunction(
            @NonNull final TransactionBody transactionBody, @NonNull final AtomicBatchConfig config) {
        try {
            final var hederaFunctionality = HapiUtils.functionOf(transactionBody);
            return config.blacklist().functionalitySet().contains(hederaFunctionality);
        } catch (final UnknownHederaFunctionality e) {
            return true;
        }
    }

    @Override
    public @NonNull Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var calculator = feeContext.feeCalculatorFactory().feeCalculator(SubType.DEFAULT);
        calculator.resetUsage();
        // adjust the price based on the number of signatures
        calculator.addVerificationsPerTransaction(Math.max(0, feeContext.numTxnSignatures() - 1));
        return calculator.calculate();
    }

    /**
     * A {@link FeeCharging} strategy that records all balance adjustments made by the delegate.
     */
    public static class RecordedFeeCharging implements FeeCharging {
        /**
         * Represents a charge that can be replayed on a {@link Context}.
         */
        public record Charge(@NonNull AccountID payerId, @NonNull Fees fees, @Nullable AccountID nodeAccountId) {
            /**
             * Replays the charge on the given {@link Context}.
             *
             * @param ctx the context to replay the charge on
             * @param cb  the callback to be used in the replay
             */
            public void replay(@NonNull final Context ctx, @NonNull ObjLongConsumer<AccountID> cb) {
                if (nodeAccountId == null) {
                    ctx.charge(payerId, fees, cb);
                } else {
                    ctx.charge(payerId, fees, nodeAccountId, cb);
                }
            }
        }

        private record ChargingEvent(
                @NonNull ReplayableFeeStreamBuilder streamBuilder, @NonNull List<Charge> charges) {}

        private final FeeCharging delegate;
        private final List<ChargingEvent> chargingEvents = new ArrayList<>();

        // We track just the final charging event of any dispatch (earlier ones would be rolled back)
        @Nullable
        private Charge finalCharge;

        public RecordedFeeCharging(@NonNull final FeeCharging delegate) {
            this.delegate = requireNonNull(delegate);
        }

        /**
         * Starts recording balance adjustments for a new charging event.
         */
        public void startRecording() {
            finalCharge = null;
        }

        /**
         * Finishes recording balance adjustments for the current {@link ReplayableFeeStreamBuilder}.
         */
        public void finishRecordingTo(@NonNull final ReplayableFeeStreamBuilder streamBuilder) {
            requireNonNull(streamBuilder);
            chargingEvents.add(new ChargingEvent(
                    streamBuilder,
                    finalCharge == null ? Collections.emptyList() : Collections.singletonList(finalCharge)));
        }

        /**
         * Invokes the given action for each recorded {@link StreamBuilder} with its associated balance adjustments.
         *
         * @param cb the action to be invoked for each recorded charging event
         */
        public void forEachRecorded(@NonNull final BiConsumer<ReplayableFeeStreamBuilder, List<Charge>> cb) {
            chargingEvents.forEach(event -> cb.accept(event.streamBuilder(), event.charges()));
        }

        @Override
        public Validation validate(
                @NonNull final Account payer,
                @NonNull final AccountID creatorId,
                @NonNull final Fees fees,
                @NonNull final TransactionBody body,
                final boolean isDuplicate,
                @NonNull final HederaFunctionality function,
                @NonNull final TransactionCategory category) {
            return delegate.validate(payer, creatorId, fees, body, isDuplicate, function, category);
        }

        @Override
        public Fees charge(@NonNull final Context ctx, @NonNull final Validation validation, @NonNull final Fees fees) {
            final var recordingContext = new RecordingContext(ctx, charge -> this.finalCharge = charge);
            return delegate.charge(recordingContext, validation, fees);
        }

        @Override
        public void refund(@NonNull final Context ctx, @NonNull final Fees fees) {
            delegate.refund(ctx, fees);
        }

        /**
         * A {@link Context} that records the balance adjustments made by the delegate.
         */
        private static class RecordingContext implements Context {
            private final Context delegate;
            private final Consumer<Charge> chargeCb;

            @Override
            public AccountID payerId() {
                return delegate.payerId();
            }

            @Override
            public AccountID nodeAccountId() {
                return delegate.nodeAccountId();
            }

            public RecordingContext(@NonNull final Context delegate, @NonNull final Consumer<Charge> chargeCb) {
                this.delegate = requireNonNull(delegate);
                this.chargeCb = requireNonNull(chargeCb);
            }

            @Override
            public Fees charge(
                    @NonNull final AccountID payerId,
                    @NonNull final Fees fees,
                    @Nullable final ObjLongConsumer<AccountID> cb) {
                final var chargedFees = delegate.charge(payerId, fees, cb);
                chargeCb.accept(new Charge(payerId, fees, null));
                return chargedFees;
            }

            @Override
            public void refund(
                    @NonNull final AccountID payerId,
                    @NonNull final Fees fees,
                    @NonNull final AccountID nodeAccountId) {
                delegate.refund(payerId, fees, nodeAccountId);
            }

            @Override
            public void refund(@NonNull final AccountID receiverId, @NonNull final Fees fees) {
                delegate.refund(receiverId, fees);
            }

            @Override
            public Fees charge(
                    @NonNull final AccountID payerId,
                    @NonNull final Fees fees,
                    @NonNull final AccountID nodeAccountId,
                    @Nullable final ObjLongConsumer<AccountID> cb) {
                final var chargedFees = delegate.charge(payerId, fees, nodeAccountId, cb);
                chargeCb.accept(new Charge(payerId, fees, nodeAccountId));
                return chargedFees;
            }

            @Override
            public TransactionCategory category() {
                return delegate.category();
            }
        }
    }

    /**
     * Converts a map of account adjustments to a {@link TransferList}.
     *
     * @param adjustments the map of account adjustments
     * @return the {@link TransferList} representing the adjustments
     */
    private static TransferList asTransferList(@NonNull final SortedMap<AccountID, Long> adjustments) {
        return new TransferList(adjustments.entrySet().stream()
                .map(entry -> AccountAmount.newBuilder()
                        .accountID(entry.getKey())
                        .amount(entry.getValue())
                        .build())
                .toList());
    }
}
