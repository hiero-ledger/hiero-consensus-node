// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_ALREADY_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_ALREADY_EXECUTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.SubType;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
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
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#SCHEDULE_DELETE}.
 */
@Singleton
public class ScheduleDeleteHandler extends AbstractScheduleHandler implements TransactionHandler {
    private final ScheduleOpsUsage scheduleOpsUsage = new ScheduleOpsUsage();

    @Inject
    public ScheduleDeleteHandler(@NonNull final ScheduleFeeCharging feeCharging) {
        super(feeCharging);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        requireNonNull(body);
        validateTruePreCheck(body.hasScheduleDelete(), INVALID_TRANSACTION_BODY);
        final var op = body.scheduleDeleteOrThrow();
        validateTruePreCheck(op.hasScheduleID(), INVALID_SCHEDULE_ID);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var scheduleStore = context.createStore(ReadableScheduleStore.class);
        final SchedulingConfig schedulingConfig = context.configuration().getConfigData(SchedulingConfig.class);
        final boolean isLongTermEnabled = schedulingConfig.longTermEnabled();
        final var op = context.body().scheduleDeleteOrThrow();
        final var schedule = getValidated(op.scheduleIDOrThrow(), scheduleStore, isLongTermEnabled);
        validateFalse(schedule.deleted(), SCHEDULE_ALREADY_DELETED);
        validateFalse(schedule.executed(), SCHEDULE_ALREADY_EXECUTED);
        validateTruePreCheck(schedule.hasAdminKey(), SCHEDULE_IS_IMMUTABLE);
        context.requireKey(schedule.adminKeyOrThrow());
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);
        final var scheduleStore = context.storeFactory().writableStore(WritableScheduleStore.class);
        final var body = context.body();
        final var op = body.scheduleDeleteOrThrow();
        final var scheduleId = op.scheduleIDOrThrow();
        final var schedulingConfig = context.configuration().getConfigData(SchedulingConfig.class);
        final boolean isLongTermEnabled = schedulingConfig.longTermEnabled();
        final var schedule = revalidateOrThrow(scheduleId, scheduleStore, isLongTermEnabled);
        validateTrue(schedule.hasAdminKey(), SCHEDULE_IS_IMMUTABLE);
        final var verificationResult = context.keyVerifier().verificationFor(schedule.adminKeyOrThrow());
        validateTrue(verificationResult.passed(), UNAUTHORIZED);
        scheduleStore.delete(scheduleId, context.consensusNow());
        context.savepointStack().getBaseBuilder(ScheduleStreamBuilder.class).scheduleID(scheduleId);
    }

    /**
     * Verify that the transaction and schedule still meet the validation criteria expressed in the
     * {@link AbstractScheduleHandler#getValidated(ScheduleID, ReadableScheduleStore, boolean)} method.
     *
     * @param scheduleId the Schedule ID of the item to mark as deleted.
     * @param scheduleStore a Readable source of Schedule data from state
     * @param isLongTermEnabled a flag indicating if long term scheduling is enabled in configuration.
     * @return a schedule metadata read from state for the ID given, if all validation checks pass
     * @throws HandleException if any validation check fails.
     */
    @NonNull
    protected Schedule revalidateOrThrow(
            @NonNull final ScheduleID scheduleId,
            @NonNull final ReadableScheduleStore scheduleStore,
            final boolean isLongTermEnabled)
            throws HandleException {
        requireNonNull(scheduleId);
        requireNonNull(scheduleStore);
        try {
            final var schedule = getValidated(scheduleId, scheduleStore, isLongTermEnabled);
            validateFalse(schedule.deleted(), SCHEDULE_ALREADY_DELETED);
            validateFalse(schedule.executed(), SCHEDULE_ALREADY_EXECUTED);
            return schedule;
        } catch (final PreCheckException e) {
            throw new HandleException(e.responseCode());
        }
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var scheduleStore = feeContext.readableStore(ReadableScheduleStore.class);
        final var op = feeContext.body();
        final var schedule = scheduleStore.get(
                op.scheduleDeleteOrElse(ScheduleDeleteTransactionBody.DEFAULT).scheduleIDOrElse(ScheduleID.DEFAULT));
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(SubType.DEFAULT)
                .legacyCalculate(sigValueObj -> usageGiven(
                        fromPbj(op),
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
            return scheduleOpsUsage.scheduleDeleteUsage(txn, sigUsage, schedule.calculatedExpirationSecond());
        } else {
            final long latestExpiry =
                    txn.getTransactionID().getTransactionValidStart().getSeconds() + scheduledTxExpiryTimeSecs;
            return scheduleOpsUsage.scheduleDeleteUsage(txn, sigUsage, latestExpiry);
        }
    }
}
