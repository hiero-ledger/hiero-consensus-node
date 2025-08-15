// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.schedule.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.IDENTICAL_SCHEDULE_ALREADY_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_ADMIN_KEY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.MISSING_EXPIRY_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULED_TRANSACTION_NOT_IN_WHITELIST;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_EXPIRATION_TIME_MUST_BE_HIGHER_THAN_CONSENSUS_TIME;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_EXPIRY_IS_BUSY;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SCHEDULE_EXPIRY_NOT_CONFIGURABLE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hedera.node.app.hapi.utils.CommonPbjConverters.fromPbj;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.childAsOrdinary;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.createProvisionalSchedule;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.functionalityForType;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.scheduledTxnIdFrom;
import static com.hedera.node.app.service.schedule.impl.handlers.HandlerUtility.transactionIdForScheduled;
import static com.hedera.node.app.spi.validation.PreCheckValidator.checkMaxCustomFees;
import static com.hedera.node.app.spi.validation.PreCheckValidator.checkMemo;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static com.hedera.node.app.spi.workflows.HandleException.validateFalse;
import static com.hedera.node.app.spi.workflows.HandleException.validateTrue;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateFalsePreCheck;
import static com.hedera.node.app.spi.workflows.PreCheckException.validateTruePreCheck;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.scheduled.SchedulableTransactionBody;
import com.hedera.hapi.node.scheduled.ScheduleCreateTransactionBody;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.schedule.ScheduledOrder;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshots;
import com.hedera.node.app.hapi.fees.usage.SigUsage;
import com.hedera.node.app.hapi.fees.usage.schedule.ScheduleOpsUsage;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.schedule.ScheduleStreamBuilder;
import com.hedera.node.app.service.schedule.WritableScheduleStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeContext;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.ids.EntityIdFactory;
import com.hedera.node.app.spi.throttle.Throttle;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.node.app.spi.workflows.TransactionHandler;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hederahashgraph.api.proto.java.FeeData;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Collections;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#SCHEDULE_CREATE}.
 */
@Singleton
public class ScheduleCreateHandler extends AbstractScheduleHandler implements TransactionHandler {
    private static final Logger log = LogManager.getLogger(ScheduleCreateHandler.class);

    private final ScheduleOpsUsage scheduleOpsUsage = new ScheduleOpsUsage();
    private final EntityIdFactory idFactory;
    private final InstantSource instantSource;
    private final Throttle.Factory throttleFactory;

    @Inject
    public ScheduleCreateHandler(
            @NonNull final EntityIdFactory idFactory,
            @NonNull final InstantSource instantSource,
            @NonNull final Throttle.Factory throttleFactory,
            @NonNull final ScheduleFeeCharging feeCharging) {
        super(feeCharging);
        this.idFactory = requireNonNull(idFactory);
        this.instantSource = requireNonNull(instantSource);
        this.throttleFactory = requireNonNull(throttleFactory);
    }

    @Override
    public void pureChecks(@NonNull final PureChecksContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        requireNonNull(body);
        validateTruePreCheck(body.hasScheduleCreate(), INVALID_TRANSACTION_BODY);
        final var op = body.scheduleCreateOrThrow();
        validateTruePreCheck(op.hasScheduledTransactionBody(), INVALID_TRANSACTION);
        // (FUTURE) Add a dedicated response code for an op waiting for an unspecified expiration time
        validateFalsePreCheck(op.waitForExpiry() && !op.hasExpirationTime(), MISSING_EXPIRY_TIME);
    }

    @Override
    public void preHandle(@NonNull final PreHandleContext context) throws PreCheckException {
        requireNonNull(context);
        final var body = context.body();
        // We ensure this exists in pureChecks()
        final var op = body.scheduleCreateOrThrow();
        final var config = context.configuration();
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        final var scheduledTxnBody = op.scheduledTransactionBodyOrThrow();
        final var scheduledFunctionality =
                functionalityForType(scheduledTxnBody.data().kind());
        checkMemo(scheduledTxnBody.memo(), hederaConfig.transactionMaxMemoUtf8Bytes());
        checkMaxCustomFees(scheduledTxnBody.maxCustomFees(), scheduledFunctionality);
        // For backward compatibility, use ACCOUNT_ID_DOES_NOT_EXIST for a nonexistent designated payer
        if (op.hasPayerAccountID()) {
            final var accountStore = context.createStore(ReadableAccountStore.class);
            final var payer = accountStore.getAccountById(op.payerAccountIDOrThrow());
            mustExist(payer, ACCOUNT_ID_DOES_NOT_EXIST);
        }
        final var schedulingConfig = config.getConfigData(SchedulingConfig.class);
        validateTruePreCheck(
                isAllowedFunction(op.scheduledTransactionBodyOrThrow(), schedulingConfig),
                SCHEDULED_TRANSACTION_NOT_IN_WHITELIST);
        // If an admin key is present, it must sign
        if (op.hasAdminKey()) {
            context.requireKey(op.adminKeyOrThrow());
        }
        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final long defaultLifetime = ledgerConfig.scheduleTxExpiryTimeSecs();
        final var schedule = createProvisionalSchedule(
                body, instantSource.instant(), defaultLifetime, schedulingConfig.longTermEnabled());
        final var transactionKeys = getRequiredKeys(schedule, context::allKeysForTransaction);
        // If the schedule payer inherits from the ScheduleCreate, it is already in the required keys
        if (op.hasPayerAccountID()) {
            context.optionalKey(transactionKeys.payerKey());
        }
        // Any required non-payer key may optionally provide its signature with the ScheduleCreate
        context.optionalKeys(transactionKeys.requiredNonPayerKeys());
    }

    @Override
    public void handle(@NonNull final HandleContext context) throws HandleException {
        requireNonNull(context);

        final var schedulingConfig = context.configuration().getConfigData(SchedulingConfig.class);
        final boolean isLongTermEnabled = schedulingConfig.longTermEnabled();
        final var ledgerConfig = context.configuration().getConfigData(LedgerConfig.class);
        final var consensusNow = context.consensusNow();
        final var defaultLifetime = ledgerConfig.scheduleTxExpiryTimeSecs();
        final var provisionalSchedule =
                createProvisionalSchedule(context.body(), consensusNow, defaultLifetime, isLongTermEnabled);
        final var now = consensusNow.getEpochSecond();
        final var then = provisionalSchedule.calculatedExpirationSecond();
        validateTrue(then > now, SCHEDULE_EXPIRATION_TIME_MUST_BE_HIGHER_THAN_CONSENSUS_TIME);
        final var maxLifetime = isLongTermEnabled
                ? schedulingConfig.maxExpirationFutureSeconds()
                : ledgerConfig.scheduleTxExpiryTimeSecs();
        validateTrue(then <= now + maxLifetime, SCHEDULE_EXPIRATION_TIME_TOO_FAR_IN_FUTURE);
        validateTrue(
                isAllowedFunction(provisionalSchedule.scheduledTransactionOrThrow(), schedulingConfig),
                SCHEDULED_TRANSACTION_NOT_IN_WHITELIST);
        context.attributeValidator().validateMemo(provisionalSchedule.memo());
        context.attributeValidator()
                .validateMemo(provisionalSchedule.scheduledTransactionOrThrow().memo());
        if (provisionalSchedule.hasAdminKey()) {
            try {
                context.attributeValidator().validateKey(provisionalSchedule.adminKeyOrThrow());
            } catch (HandleException e) {
                throw new HandleException(INVALID_ADMIN_KEY);
            }
        }
        final var validationResult = validate(provisionalSchedule, consensusNow, isLongTermEnabled);
        validateTrue(isMaybeExecutable(validationResult), validationResult);

        validateFalse(
                !isLongTermEnabled && provisionalSchedule.providedExpirationSecond() != 0,
                SCHEDULE_EXPIRY_NOT_CONFIGURABLE);

        // Note that we must store the original ScheduleCreate transaction body in the Schedule so
        // we can compare those bytes to any new ScheduleCreate transaction for detecting duplicate
        // ScheduleCreate transactions. SchedulesByEquality is the virtual map for that task.
        final var scheduleStore = context.storeFactory().writableStore(WritableScheduleStore.class);
        final var possibleDuplicateId = scheduleStore.getByEquality(provisionalSchedule);
        final var possibleDuplicate = possibleDuplicateId == null ? null : scheduleStore.get(possibleDuplicateId);
        final var duplicate = maybeDuplicate(provisionalSchedule, possibleDuplicate);
        if (duplicate != null) {
            final var scheduledTxnId = scheduledTxnIdFrom(
                    duplicate.originalCreateTransactionOrThrow().transactionIDOrThrow());
            context.savepointStack()
                    .getBaseBuilder(ScheduleStreamBuilder.class)
                    .scheduleID(duplicate.scheduleId())
                    .scheduledTransactionID(scheduledTxnId);
            throw new HandleException(IDENTICAL_SCHEDULE_ALREADY_CREATED);
        }
        validateTrue(
                scheduleStore.numSchedulesInState() + 1 <= schedulingConfig.maxNumber(),
                MAX_ENTITIES_IN_PRICE_REGIME_HAVE_BEEN_CREATED);
        final var capacityFraction = schedulingConfig.schedulableCapacityFraction();
        final var usageSnapshots = scheduleStore.usageSnapshotsForScheduled(then);
        final var throttle =
                upToDateThrottle(then, capacityFraction.asApproxCapacitySplit(), usageSnapshots, scheduleStore);
        validateTrue(
                throttle.allow(
                        provisionalSchedule.payerAccountIdOrThrow(),
                        childAsOrdinary(provisionalSchedule),
                        functionOf(provisionalSchedule),
                        Instant.ofEpochSecond(then)),
                SCHEDULE_EXPIRY_IS_BUSY);
        scheduleStore.trackUsage(then, throttle.usageSnapshots());

        // With all validations done, we check if the new schedule is already executable
        final var transactionKeys = getTransactionKeysOrThrow(provisionalSchedule, context::allKeysForTransaction);
        final var requiredKeys = allRequiredKeys(transactionKeys);
        final var signatories =
                newSignatories(context.keyVerifier().authorizingSimpleKeys(), Collections.emptyList(), requiredKeys);
        final var schedulingTxnId =
                provisionalSchedule.originalCreateTransactionOrThrow().transactionIDOrThrow();
        final var schedulerId = schedulingTxnId.accountIDOrThrow();
        final var scheduleId =
                idFactory.newScheduleId(context.entityNumGenerator().newEntityNum());
        var schedule = provisionalSchedule
                .copyBuilder()
                .scheduleId(scheduleId)
                .schedulerAccountId(schedulerId)
                .signatories(signatories)
                .build();
        if (tryToExecuteSchedule(context, schedule, requiredKeys, validationResult, isLongTermEnabled)) {
            schedule = markedExecuted(schedule, consensusNow);
        }
        scheduleStore.putAndIncrementCount(schedule);
        context.savepointStack()
                .getBaseBuilder(ScheduleStreamBuilder.class)
                .scheduleID(schedule.scheduleId())
                .scheduledTransactionID(transactionIdForScheduled(schedule));
    }

    @NonNull
    @Override
    public Fees calculateFees(@NonNull final FeeContext feeContext) {
        requireNonNull(feeContext);
        final var body = feeContext.body();
        final var config = feeContext.configuration();
        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var schedulingConfig = config.getConfigData(SchedulingConfig.class);
        final var subType = body.scheduleCreateOrElse(ScheduleCreateTransactionBody.DEFAULT)
                        .scheduledTransactionBodyOrElse(SchedulableTransactionBody.DEFAULT)
                        .hasContractCall()
                ? SCHEDULE_CREATE_CONTRACT_CALL
                : DEFAULT;
        return feeContext
                .feeCalculatorFactory()
                .feeCalculator(subType)
                .legacyCalculate(sigValueObj -> usageGiven(
                        fromPbj(body),
                        sigValueObj,
                        schedulingConfig.longTermEnabled(),
                        ledgerConfig.scheduleTxExpiryTimeSecs()));
    }

    private @NonNull FeeData usageGiven(
            @NonNull final com.hederahashgraph.api.proto.java.TransactionBody txn,
            @NonNull final SigValueObj svo,
            final boolean longTermEnabled,
            final long scheduledTxExpiryTimeSecs) {
        final var op = txn.getScheduleCreate();
        final var sigUsage = new SigUsage(svo.getTotalSigCount(), svo.getSignatureSize(), svo.getPayerAcctSigCount());
        final long lifetimeSecs;
        if (op.hasExpirationTime() && longTermEnabled) {
            lifetimeSecs = Math.max(
                    0L,
                    op.getExpirationTime().getSeconds()
                            - txn.getTransactionID().getTransactionValidStart().getSeconds());
        } else {
            lifetimeSecs = scheduledTxExpiryTimeSecs;
        }
        return scheduleOpsUsage.scheduleCreateUsage(txn, sigUsage, lifetimeSecs);
    }

    private @Nullable Schedule maybeDuplicate(@NonNull final Schedule schedule, @Nullable final Schedule duplicate) {
        if (duplicate == null) {
            return null;
        }
        if (areIdentical(duplicate, schedule)) {
            return duplicate;
        }
        return null;
    }

    private boolean areIdentical(@NonNull final Schedule candidate, @NonNull final Schedule requested) {
        return candidate.waitForExpiry() == requested.waitForExpiry()
                && candidate.providedExpirationSecond() == requested.providedExpirationSecond()
                && Objects.equals(candidate.memo(), requested.memo())
                && Objects.equals(candidate.adminKey(), requested.adminKey())
                // @note We should check scheduler here, but mono doesn't, so we cannot either, yet.
                && Objects.equals(candidate.scheduledTransaction(), requested.scheduledTransaction());
    }

    private boolean isAllowedFunction(
            @NonNull final SchedulableTransactionBody body, @NonNull final SchedulingConfig config) {
        final var scheduledFunctionality = functionalityForType(body.data().kind());
        return config.whitelist().functionalitySet().contains(scheduledFunctionality);
    }

    private HederaFunctionality functionOf(@NonNull final Schedule schedule) {
        return functionalityForType(
                schedule.scheduledTransactionOrThrow().data().kind());
    }

    /**
     * Attempts to recover a throttle from the given usage snapshots, or creates a new throttle if the recovery fails.
     * (This edge case can occur if the network throttle definitions changed since a transaction was last scheduled
     * in the given second and snapshots were taken.)
     * @param then the second for which the throttle is being recovered
     * @param capacitySplit the capacity split for the throttle
     * @param usageSnapshots the usage snapshots to recover from
     * @return the throttle
     */
    private Throttle upToDateThrottle(
            final long then,
            final int capacitySplit,
            @Nullable final ThrottleUsageSnapshots usageSnapshots,
            @NonNull final WritableScheduleStore scheduleStore) {
        requireNonNull(scheduleStore);
        try {
            return throttleFactory.newThrottle(capacitySplit, usageSnapshots);
        } catch (Exception e) {
            final var instantThen = Instant.ofEpochSecond(then);
            log.info(
                    "Could not recreate throttle at {} from {} ({}), rebuilding with up-to-date throttle",
                    instantThen,
                    usageSnapshots,
                    e.getMessage());
            final var throttle = throttleFactory.newThrottle(capacitySplit, null);
            final var counts = requireNonNull(scheduleStore.scheduledCountsAt(then));
            final int n = counts.numberScheduled();
            for (int i = 0; i < n; i++) {
                final var scheduleId = requireNonNull(scheduleStore.getByOrder(new ScheduledOrder(then, i)));
                final var schedule = requireNonNull(scheduleStore.get(scheduleId));
                // Consume capacity from every already-scheduled transaction in the new throttle
                throttle.allow(
                        schedule.payerAccountIdOrThrow(),
                        childAsOrdinary(schedule),
                        functionOf(schedule),
                        Instant.ofEpochSecond(then));
            }
            log.info("Rebuilt throttle at {} from {} scheduled transactions", instantThen, n);
            return throttle;
        }
    }
}
