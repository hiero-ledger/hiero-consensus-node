// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NO_NEW_VALID_SIGNATURES;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.transactionIdForScheduled;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.scheduled.ScheduleSignTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.schedule.ScheduleOpsUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.schedule.ScheduleStreamBuilder;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#SCHEDULE_SIGN}.
 */
@Singleton
public class ScheduleSignHandler extends AbstractScheduleHandler implements TransactionHandler {
    private final ScheduleOpsUsage scheduleOpsUsage = new ScheduleOpsUsage();

    @Inject
    public ScheduleSignHandler(@NonNull final ScheduleFeeCharging feeCharging) {
        super(feeCharging);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        requireNonNull(body);
        validateTruePreCheck(body.hasScheduleSign(), INVALID_TRANSACTION_BODY);
        final var op = body.scheduleSignOrThrow();
        validateTruePreCheck(op.hasScheduleID(), INVALID_SCHEDULE_ID);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var op = context.body().scheduleSignOrThrow();
        final var scheduleStore = context.createStore(ReadableScheduleStore.class);
        final var schedulingConfig = context.configuration().getConfigData(SchedulingConfig.class);
        final boolean isLongTermEnabled = schedulingConfig.longTermEnabled();
        final var schedule = getValidated(op.scheduleIDOrThrow(), scheduleStore, isLongTermEnabled);
        final var requiredKeys = getRequiredKeys(schedule, context::allKeysForTransaction);
        context.optionalKey(requiredKeys.payerKey());
        context.optionalKeys(requiredKeys.requiredNonPayerKeys());
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var op = context.body().scheduleSignOrThrow();
        final var scheduleStore = context.storeFactory().writableStore(WritableScheduleStore.class);
        // Non-final because we may update signatories and/or mark it as executed before putting it back
        var schedule = scheduleStore.get(op.scheduleIDOrThrow());

        final var consensusNow = context.consensusNow();
        final var schedulingConfig = context.configuration().getConfigData(SchedulingConfig.class);
        final boolean isLongTermEnabled = schedulingConfig.longTermEnabled();
        final var validationResult = validate(schedule, consensusNow, isLongTermEnabled);
        validateTrue(isMaybeExecutable(validationResult), validationResult);

        // With all validations done, we update the signatories on the schedule
        final var transactionKeys = getTransactionKeysOrThrow(schedule, context::allKeysForTransaction);
        final var requiredKeys = allRequiredKeys(transactionKeys);
        final var signatories = schedule.signatories();
        final var newSignatories =
                newSignatories(context.keyVerifier().authorizingSimpleKeys(), signatories, requiredKeys);
        schedule = schedule.copyBuilder().signatories(newSignatories).build();
        if (isLongTermEnabled && schedule.waitForExpiry()) {
            validateTrue(!newSignatories.equals(signatories), NO_NEW_VALID_SIGNATURES);
            scheduleStore.put(schedule);
        } else {
            if (tryToExecuteSchedule(context, schedule, requiredKeys, validationResult, isLongTermEnabled)) {
                scheduleStore.put(markedExecuted(schedule, consensusNow));
            } else {
                validateTrue(!newSignatories.equals(signatories), NO_NEW_VALID_SIGNATURES);
                scheduleStore.put(schedule);
            }
        }
        context.savepointStack()
                .getBaseBuilder(ScheduleStreamBuilder.class)
                .scheduledTransactionID(transactionIdForScheduled(schedule));
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var body = feeContext.body();
        final var scheduleStore = feeContext.readableStore(ReadableScheduleStore.class);
        final var schedule = scheduleStore.get(
                body.scheduleSignOrElse(ScheduleSignTransactionBody.DEFAULT).scheduleIDOrElse(ScheduleID.DEFAULT));
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGiven(
                        fromPbj(body),
                        sigValueObj,
                        schedule,
                        feeContext
                                .configuration()
                                .getConfigData(LedgerConfig.class)
                                .scheduleTxExpiryTimeSecs()));
    }

    private FeeData usageGiven(
            @NonNull final com.hederahashgraph.api.proto.java.TransactionBody txn,
            @NonNull final SigValueObj svo,
            @Nullable final Schedule schedule,
            final long scheduledTxExpiryTimeSecs) {
        final var sigUsage = new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
        if (schedule != null) {
            return scheduleOpsUsage.scheduleSignUsage(txn, sigUsage, schedule.calculatedExpirationSecond());
        } else {
            final long latestExpiry =
                    txn.getTransactionID().getTransactionValidStart().getSeconds() + scheduledTxExpiryTimeSecs;
            return scheduleOpsUsage.scheduleSignUsage(txn, sigUsage, latestExpiry);
        }
    }
}
