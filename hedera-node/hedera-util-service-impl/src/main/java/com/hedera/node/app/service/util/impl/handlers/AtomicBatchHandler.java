/*
 * Copyright (C) 2022-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.util.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INNER_TRANSACTION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_BATCH_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.util.HapiUtils.ACCOUNT_ID_COMPARATOR;
import static com.hedera.node.app.spi.workflows.DispatchOptions.atomicBatchDispatch;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountAmount;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.base.Transaction;
import com.hedera.hapi.node.base.TransferList;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.util.impl.records.ReplayableFeeStreamBuilder;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.data.AtomicBatchConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final Function<Transaction, TransactionBody> bodyParser;

    /**
     * Constructs a {@link AtomicBatchHandler}
     */
    @Inject
    public AtomicBatchHandler(
            @NonNull final AppContext appContext, @NonNull final Function<Transaction, TransactionBody> bodyParser) {
        requireNonNull(appContext);
        this.bodyParser = requireNonNull(bodyParser);
        this.appFeeCharging = appContext.feeChargingSupplier();
    }

    /**
     * Performs checks independent of state or context.
     *
     * @param context the context to check
     */
    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().atomicBatchOrThrow();
        validateFalsePreCheck(context.body().hasBatchKey(), MISSING_BATCH_KEY);
        for (final var transaction : op.transactions()) {
            validateTruePreCheck(transaction.hasBody(), INVALID_TRANSACTION);

            // validate batch key exists on each inner transaction
            validateTruePreCheck(transaction.bodyOrThrow().hasBatchKey(), MISSING_BATCH_KEY);
        }
    }

    /**
     * This method is called during the pre-handle workflow.
     *
     * @param context the {@link PreHandleContext} which collects all information
     * @throws PreCheckException if any issue happens on the pre handle level
     */
    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        // TODO
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().atomicBatchOrThrow();
        if (!context.configuration().getConfigData(AtomicBatchConfig.class).isEnabled()) {
            throw new HandleException(NOT_SUPPORTED);
        }
        final var txnBodies = new ArrayList<TransactionBody>();
        for (final var transaction : op.transactions()) {
            try {
                txnBodies.add(bodyParser.apply(transaction));
            } catch (HandleException e) {
                // Do we need to keep the specific ResponseCodeEnum here?
                throw new HandleException(INNER_TRANSACTION_FAILED);
            }
        }
        // The parsing check, timebox, and duplication checks are done in the pre-handle workflow
        // So, no need to repeat here
        // dispatch all the inner transactions
        final var recordedFeeCharging = new RecordedFeeCharging(appFeeCharging.get());
        for (final var body : txnBodies) {
            final var payerId = body.transactionIDOrThrow().accountIDOrThrow();
            // all the inner transactions' keys are verified in PreHandleWorkflow
            final var dispatchOptions =
                    atomicBatchDispatch(payerId, body, ReplayableFeeStreamBuilder.class, recordedFeeCharging);
            recordedFeeCharging.startRecording();
            final var streamBuilder = context.dispatch(dispatchOptions);
            recordedFeeCharging.finishRecordingTo(streamBuilder);
            if (streamBuilder.status() != SUCCESS) {
                throw new HandleException(
                        INNER_TRANSACTION_FAILED,
                        ctx -> recordedFeeCharging.forEachRecorded((builder, charges) -> {
                            final var adjustments = new TreeMap<AccountID, Long>(ACCOUNT_ID_COMPARATOR);
                            charges.forEach(charge ->
                                    charge.replay(ctx, (id, amount) -> adjustments.merge(id, amount, Long::sum)));
                            builder.setReplayedFees(asTransferList(adjustments));
                        }));
            }
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
    static class RecordedFeeCharging implements FeeCharging {
        /**
         * Represents a charge that can be replayed on a {@link FeeCharging.Context}.
         */
        public record Charge(@NonNull AccountID payerId, @NonNull Fees fees, @Nullable AccountID nodeAccountId) {
            /**
             * Replays the charge on the given {@link FeeCharging.Context}.
             * @param ctx the context to replay the charge on
             * @param cb the callback to be used in the replay
             */
            public void replay(@NonNull final FeeCharging.Context ctx, @NonNull ObjLongConsumer<AccountID> cb) {
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
                @NonNull final HandleContext.TransactionCategory category) {
            return delegate.validate(payer, creatorId, fees, body, isDuplicate, function, category);
        }

        @Override
        public void charge(@NonNull final Context ctx, @NonNull final Validation validation, @NonNull final Fees fees) {
            final var recordingContext = new RecordingContext(ctx, charge -> this.finalCharge = charge);
            delegate.charge(recordingContext, validation, fees);
        }

        /**
         * A {@link Context} that records the balance adjustments made by the delegate.
         */
        private static class RecordingContext implements Context {
            private final Context delegate;
            private final Consumer<Charge> chargeCb;

            public RecordingContext(@NonNull final Context delegate, @NonNull final Consumer<Charge> chargeCb) {
                this.delegate = requireNonNull(delegate);
                this.chargeCb = requireNonNull(chargeCb);
            }

            @Override
            public void charge(
                    @NonNull final AccountID payerId,
                    @NonNull final Fees fees,
                    @Nullable final ObjLongConsumer<AccountID> cb) {
                delegate.charge(payerId, fees, cb);
                chargeCb.accept(new Charge(payerId, fees, null));
            }

            @Override
            public void charge(
                    @NonNull final AccountID payerId,
                    @NonNull final Fees fees,
                    @NonNull final AccountID nodeAccountId,
                    @Nullable final ObjLongConsumer<AccountID> cb) {
                delegate.charge(payerId, fees, nodeAccountId, cb);
                chargeCb.accept(new Charge(payerId, fees, nodeAccountId));
            }

            @Override
            public HandleContext.TransactionCategory category() {
                return delegate.category();
            }
        }
    }

    /**
     * Converts a map of account adjustments to a {@link TransferList}.
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
