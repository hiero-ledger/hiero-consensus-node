// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static com.hedera.hapi.node.base.HederaFunctionality.CLPR_SUBMIT_BUNDLE;
import static com.hedera.hapi.node.base.HederaFunctionality.ETHEREUM_TRANSACTION;
import static com.hedera.hapi.node.base.HederaFunctionality.HOOK_DISPATCH;
import static com.hedera.hapi.node.base.HederaFunctionality.NODE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.SYSTEM_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.AUTHORIZATION_FAILED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.ENTITY_NOT_ALLOWED_TO_DELETE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.FAIL_INVALID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_SIGNATURE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.UNAUTHORIZED;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.BATCH_INNER;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.CHILD;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.node.app.fees.AppFeeCharging;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.fees.FeeAccumulator;
import com.hedera.node.app.service.contract.impl.handlers.EthereumTransactionHandler;
import com.hedera.node.app.spi.authorization.Authorizer;
import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.app.workflows.dispatcher.TransactionDispatcher;
import com.hedera.node.app.workflows.handle.dispatch.DispatchValidator;
import com.hedera.node.app.workflows.handle.dispatch.RecordFinalizer;
import com.hedera.node.app.workflows.handle.stack.SavepointStackImpl;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions;
import com.hedera.node.app.workflows.handle.steps.HollowAccountCompletions.Details;
import com.hedera.node.app.workflows.handle.steps.PlatformStateUpdates;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates;
import com.hedera.node.app.workflows.handle.throttle.DispatchUsageManager;
import com.hedera.node.app.workflows.handle.throttle.ThrottleException;
import com.hedera.node.config.data.ContractsConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * This class has the common logic that is executed for a user dispatch and a child dispatch transactions.
 * This will charge the fees to the creator if there is a node due diligence failure. Otherwise, charges the fees to the
 * payer and executes the business logic for the given dispatch, guaranteeing that the changes committed to its stack
 * are exactly reflected in its recordBuilder. At the end, it will finalize the record and commit the stack.
 */
@Singleton
public class DispatchProcessor {
    private static final Logger logger = LogManager.getLogger(DispatchProcessor.class);

    // Functions that are only usable as child dispatches from handlers
    private static final Set<HederaFunctionality> HANDLER_STEP_FUNCTIONS = EnumSet.of(HOOK_DISPATCH);

    private final Authorizer authorizer;
    private final DispatchValidator validator;
    private final RecordFinalizer recordFinalizer;
    private final SystemFileUpdates systemFileUpdates;
    private final PlatformStateUpdates platformStateUpdates;
    private final DispatchUsageManager dispatchUsageManager;
    private final ExchangeRateManager exchangeRateManager;
    private final TransactionDispatcher dispatcher;
    private final EthereumTransactionHandler ethereumTransactionHandler;
    private final NetworkInfo networkInfo;
    private final OpWorkflowMetrics workflowMetrics;
    private final AppFeeCharging appFeeCharging;

    /**
     * A system-file facility update a dispatch of the current transaction applied in memory, with the file contents
     * it was updated from and the savepoint stack of that dispatch. The update is anchored to the savepoint holding
     * the dispatch's changes, so a rollback of that savepoint, or of any savepoint or stack those changes were
     * committed into, recovers the update right away; and once the transaction commits any facility whose file no
     * longer holds the applied contents is re-derived from the committed state.
     */
    private record PendingResync(
            @NonNull SystemFileUpdates.ResyncTarget target,
            @NonNull SavepointStackImpl stack,
            boolean recoveryFailed) {}

    // In recording order, so updates recovered together can share the baseline captured before the first of them,
    // and several updates of one facility in a transaction are evaluated from the earliest to the latest
    private final List<PendingResync> pendingResyncs = new ArrayList<>();
    // A single instance, so a rollback that discards several updates reports them to this processor together
    private final SavepointStackImpl.RollbackObserver rollbackObserver = this::recoverDiscardedUpdates;
    private boolean pendingNodeInfoResync;

    @Inject
    public DispatchProcessor(
            @NonNull final Authorizer authorizer,
            @NonNull final DispatchValidator validator,
            @NonNull final RecordFinalizer recordFinalizer,
            @NonNull final SystemFileUpdates systemFileUpdates,
            @NonNull final PlatformStateUpdates platformStateUpdates,
            @NonNull final DispatchUsageManager dispatchUsageManager,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final TransactionDispatcher dispatcher,
            @NonNull final EthereumTransactionHandler ethereumTransactionHandler,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final OpWorkflowMetrics workflowMetrics,
            @NonNull final AppFeeCharging appFeeCharging) {
        this.authorizer = requireNonNull(authorizer);
        this.validator = requireNonNull(validator);
        this.recordFinalizer = requireNonNull(recordFinalizer);
        this.systemFileUpdates = requireNonNull(systemFileUpdates);
        this.platformStateUpdates = requireNonNull(platformStateUpdates);
        this.dispatchUsageManager = requireNonNull(dispatchUsageManager);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.dispatcher = requireNonNull(dispatcher);
        this.ethereumTransactionHandler = requireNonNull(ethereumTransactionHandler);
        this.networkInfo = requireNonNull(networkInfo);
        this.workflowMetrics = requireNonNull(workflowMetrics);
        this.appFeeCharging = requireNonNull(appFeeCharging);
    }

    /**
     * This method is responsible for charging the fees and tries to execute the
     * business logic for the given dispatch, guaranteeing that the changes committed
     * to its stack are exactly reflected in its recordBuilder. At the end, it will
     * finalize the record and commit the stack.
     *
     * @param dispatch the dispatch to be processed
     */
    public void processDispatch(@NonNull final Dispatch dispatch) {
        processDispatch(dispatch, null);
    }

    /**
     * This method is responsible for charging the fees and tries to execute the
     * business logic for the given dispatch, guaranteeing that the changes committed
     * to its stack are exactly reflected in its recordBuilder. At the end, it will
     * finalize the record and commit the stack.
     *
     * @param dispatch the dispatch to be processed
     * @param hollowAccountCompletionsDetails optional hollow-account setup dispatches to replay after rollback
     */
    public void processDispatch(
            @NonNull final Dispatch dispatch, @Nullable final Details hollowAccountCompletionsDetails) {
        processDispatch(dispatch, hollowAccountCompletionsDetails, false);
    }

    /**
     * Processes a submitted or scheduled transaction using normal fee charging, with an optional block-capacity
     * rejection.
     */
    public void processDispatch(
            @NonNull final Dispatch dispatch,
            @Nullable final Details hollowAccountCompletionsDetails,
            final boolean blockFull) {
        requireNonNull(dispatch);
        final var validation = validator.validateFeeChargingScenario(dispatch);
        if (!validation.creatorDidDueDiligence()) {
            if (isClprSubmitBundle(dispatch)) {
                logger.debug(
                        "[CLPR-DISPATCH] creator due diligence failed; charging creator txId={} status={}",
                        dispatch.txnInfo().transactionID(),
                        validation.errorStatusOrThrow());
            }
            chargeCreator(dispatch, validation);
        } else {
            final var fees = chargePayer(dispatch, validation, false);
            if (!alreadyFailed(dispatch, validation)) {
                tryHandle(dispatch, validation, fees, hollowAccountCompletionsDetails, blockFull);
            }
        }
        final var isRoot = dispatch.stack().isRoot();
        if (isRoot) {
            // The transaction's outcome is final here: a failed root dispatch has already been rolled back, and a
            // successful one holds its final changes in the stack. Every rollback of a savepoint or stack holding an
            // update, including a contract frame reverting around a completed dispatch, was recovered when it
            // happened. This pass is the backstop for an update whose rollback was not observed, comparing memory
            // against the stack before the throttle usage is finalized and saved, so a restored usage is what gets
            // saved to state. The targets stay pending until the commit, so an abandoned transaction can still
            // recover them from the state that remains.
            resyncPendingFacilities(dispatch.stack(), false);
        }
        dispatchUsageManager.finalizeAndSaveUsage(dispatch);
        recordFinalizer.finalizeRecord(dispatch);
        dispatch.stack().commitFullStack();
        if (isRoot) {
            completeResyncsAfterCommit();
        }
    }

    /**
     * Re-derives, from the given state, every in-memory facility and the node info that a dispatch updated in memory
     * but whose transaction did not complete normally, and clears the pending updates. Called when the handle
     * workflow abandons a transaction after an unexpected failure, so memory matches the state that remains.
     *
     * @param state the state that remains after the abandoned transaction
     */
    public void resyncAfterAbandonedTransaction(@NonNull final State state) {
        requireNonNull(state);
        try {
            // Everything the transaction applied is gone with it, so every pending update is treated as rolled back
            resyncPendingFacilities(state, true);
        } finally {
            completeResyncsAfterCommit();
        }
    }

    /**
     * Re-derives from the given state the pending facilities that need it (all of them if {@code force}, otherwise
     * those {@link SystemFileUpdates#needsResync(State, SystemFileUpdates.ResyncTarget)} selects) and refreshes the
     * node info if a {@code NodeUpdate} ran. Leaves the updates pending; {@link #completeResyncsAfterCommit()} clears
     * them.
     *
     * <p>A facility updated more than once in the transaction is evaluated once, against the latest applied contents;
     * if it must be re-derived, the recovery baselines come from the first update after the last one the state still
     * holds, since that is the update whose rollback discarded them.
     *
     * @param state the state to re-derive from
     * @param force whether to re-derive every pending facility regardless of what the state shows
     */
    private void resyncPendingFacilities(@NonNull final State state, final boolean force) {
        // Each facility to recover, keyed by the recording position of the update its baselines come from, so the
        // throttle baseline shared across facilities is the chronologically earliest one selected
        final Map<Integer, SystemFileUpdates.ResyncTarget> targetsByBaselineOrder = new TreeMap<>();
        for (final var updates : pendingUpdatesByFacility().values()) {
            final var latest = updates.getLast();
            if (!force && !systemFileUpdates.needsResync(state, latest)) {
                continue;
            }
            // The updates up to the last one whose applied contents the state still holds survived; the first update
            // after that is the one whose rollback discarded the baselines, so recovery restores its baselines. If
            // none survived (or everything is forced), the first update's baselines are the pre-transaction ones.
            var baselines = updates.getFirst();
            if (!force) {
                for (int i = updates.size() - 1; i >= 0; i--) {
                    if (!systemFileUpdates.needsResync(state, updates.get(i))) {
                        baselines = updates.get(Math.min(i + 1, updates.size() - 1));
                        break;
                    }
                }
            }
            final var target = composed(latest, baselines);
            targetsByBaselineOrder.put(recordingIndexOf(baselines), force ? target.asRolledBack() : target);
        }
        final List<SystemFileUpdates.ResyncTarget> targets = new ArrayList<>(targetsByBaselineOrder.values());
        Throwable firstFailure = recoverTargets(state, targets, null);
        if (pendingNodeInfoResync) {
            try {
                networkInfo.updateFrom(state);
            } catch (final Throwable e) {
                // Like a facility failure, a node-info failure must not mask an earlier facility failure; record it
                // (or attach it to the first failure as suppressed).
                logger.error("Failed to re-derive node info from committed state", e);
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        if (firstFailure != null) {
            // firstFailure was captured across iterations, so precise rethrow does not apply; rethrow the exact
            // instance without assuming it is unchecked (a sneaky-thrown checked Throwable must not become a CCE).
            sneakyRethrow(firstFailure);
        }
    }

    /**
     * Once the transaction's outcome is final: returns block node streaming to the configuration in effect, queues any
     * block node streaming transition the committed writer mode calls for (judged against the mode in effect before
     * the first configuration update of the transaction), and clears the pending updates and the node info flag.
     */
    private void completeResyncsAfterCommit() {
        try {
            // The configuration in effect is a committed one again. Released before a transition is queued, since
            // the transition runs on another thread and must act on the committed configuration, not the held one
            systemFileUpdates.releaseStreamingConfiguration();
            // Either configuration file can set the writer mode, and the earliest update of either recorded the
            // mode from before the transaction
            for (final var pending : pendingResyncs) {
                if (isConfigurationFacility(pending.target().facility())) {
                    systemFileUpdates.reconcileBlockNodeStreaming(pending.target());
                    break;
                }
            }
        } finally {
            pendingResyncs.clear();
            pendingNodeInfoResync = false;
        }
    }

    /**
     * Returns whether an update of the given facility replaces the configuration, which both the network-properties
     * and the HAPI-permissions files are sources of.
     */
    private static boolean isConfigurationFacility(@NonNull final SystemFileUpdates.ResyncTarget.Facility facility) {
        return facility == SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES
                || facility == SystemFileUpdates.ResyncTarget.Facility.HAPI_PERMISSIONS;
    }

    /**
     * Recovers the pending facility updates held by savepoints that were just rolled back, whether this processor
     * rolled back a dispatch or a handler rolled back a savepoint (such as a reverted contract frame) that the changes
     * of a completed dispatch had been committed into.
     *
     * @param stack the stack the rollback was performed on
     * @param anchors the updates the discarded savepoints held, in recording order
     */
    private void recoverDiscardedUpdates(@NonNull final SavepointStackImpl stack, @NonNull final List<Object> anchors) {
        // In recording order: the discarded updates that are still pending (one already recovered or cleared is not),
        // together with any update made within this stack whose earlier recovery failed. Such an update is known to be
        // rolled back and is no longer anchored; recovering it here with the others lets them all restore the baselines
        // captured before the first of them.
        final List<PendingResync> discarded = new ArrayList<>();
        for (final var pending : pendingResyncs) {
            if (containsInstance(anchors, pending) || (pending.recoveryFailed() && isWithin(pending.stack(), stack))) {
                discarded.add(pending);
            }
        }
        recoverRolledBack(stack, discarded);
    }

    /**
     * Recovers every pending facility update recorded by the dispatch with the given stack or by a dispatch nested
     * within it, which has just been rolled back. The rollback itself already recovered the updates anchored in the
     * stack; this catches an update that never reached it, such as one from a nested dispatch that failed before
     * committing into its parent, or one whose first recovery attempt failed.
     *
     * @param rolledBackStack the stack that was just rolled back
     */
    private void recoverRolledBackTargets(@NonNull final SavepointStackImpl rolledBackStack) {
        final List<PendingResync> discarded = new ArrayList<>();
        for (final var pending : pendingResyncs) {
            if (isWithin(pending.stack(), rolledBackStack)) {
                discarded.add(pending);
            }
        }
        recoverRolledBack(rolledBackStack, discarded);
    }

    /** Returns whether the given stack is the given enclosing stack or the stack of a dispatch nested within it. */
    private static boolean isWithin(
            @NonNull final SavepointStackImpl stack, @NonNull final SavepointStackImpl enclosing) {
        for (var candidate = stack; candidate != null; candidate = candidate.parent()) {
            if (candidate == enclosing) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsInstance(@NonNull final List<?> values, @NonNull final Object value) {
        for (final var candidate : values) {
            if (candidate == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recovers, right away, the given pending facility updates, whose changes were just rolled back. The given stack
     * shows the state without those changes, so each facility is re-derived from it, restoring the throttle usage
     * captured before the first of these updates; work done later in the transaction then accumulates on top of that.
     * Several rolled-back updates of one facility are recovered once, from the latest one's file with the earliest
     * one's baselines. A recovered update is no longer pending, so the root-level pass does not restore it again; a
     * facility whose recovery fails stays pending with the baselines used here, so a later pass retries with the same
     * ones.
     *
     * @param rolledBackStack the stack showing the state after the rollback
     * @param discarded the pending updates that were rolled back, in recording order
     */
    private void recoverRolledBack(
            @NonNull final SavepointStackImpl rolledBackStack, @NonNull final List<PendingResync> discarded) {
        if (discarded.isEmpty()) {
            return;
        }
        // The rolled-back updates, grouped per facility in recording order
        final Map<SystemFileUpdates.ResyncTarget.Facility, List<PendingResync>> rolledBack = new LinkedHashMap<>();
        for (final var pending : discarded) {
            rolledBack
                    .computeIfAbsent(pending.target().facility(), k -> new ArrayList<>())
                    .add(pending);
        }
        // One recovery per facility: the latest rolled-back update's file and the earliest one's baselines
        final List<SystemFileUpdates.ResyncTarget> targets = new ArrayList<>();
        for (final var updates : rolledBack.values()) {
            targets.add(composed(updates.getLast().target(), updates.getFirst().target())
                    .asRolledBack());
        }
        final var shared = withSharedThrottleBaseline(targets);
        Throwable firstFailure = null;
        int i = 0;
        for (final var updates : rolledBack.values()) {
            final var target = shared.get(i++);
            try {
                systemFileUpdates.resyncFromState(rolledBackStack, target);
                removePending(updates);
            } catch (final Throwable e) {
                logger.error("Failed to re-derive facility {} from the rolled-back state", target.facility(), e);
                // Keep a single pending update for the facility carrying the baselines used here (including the
                // shared throttle usage), so the abandonment pass retries with the same baselines
                final var retry = new PendingResync(target, updates.getFirst().stack(), true);
                pendingResyncs.set(pendingIndexOf(updates.getFirst()), retry);
                removePending(updates.subList(1, updates.size()));
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        if (firstFailure != null) {
            sneakyRethrow(firstFailure);
        }
    }

    /**
     * Re-derives the given targets from the given state, sharing the throttle usage baseline captured before the
     * first of them (see {@link #withSharedThrottleBaseline(List)}). One facility failing must not leave the others
     * out of step; the first failure is returned, with later ones attached as suppressed, for the caller to rethrow.
     *
     * @param state the state to re-derive from
     * @param targets the targets to recover, in the order their updates were recorded
     * @param firstFailure an earlier failure to attach later ones to, or null
     * @return the first failure, or null if every target was recovered
     */
    @Nullable
    private Throwable recoverTargets(
            @NonNull final State state,
            @NonNull final List<SystemFileUpdates.ResyncTarget> targets,
            @Nullable Throwable firstFailure) {
        for (final var target : withSharedThrottleBaseline(targets)) {
            try {
                systemFileUpdates.resyncFromState(state, target);
            } catch (final Throwable e) {
                logger.error("Failed to re-derive facility {} from committed state", target.facility(), e);
                if (firstFailure == null) {
                    firstFailure = e;
                } else {
                    firstFailure.addSuppressed(e);
                }
            }
        }
        return firstFailure;
    }

    /** Removes exactly the given pending updates, not others that merely equal them. */
    private void removePending(@NonNull final List<PendingResync> updates) {
        pendingResyncs.removeIf(pending -> containsInstance(updates, pending));
    }

    private int pendingIndexOf(@NonNull final PendingResync update) {
        for (int i = 0; i < pendingResyncs.size(); i++) {
            if (pendingResyncs.get(i) == update) {
                return i;
            }
        }
        throw new IllegalStateException("Update " + update + " is not pending");
    }

    /** Returns the position at which the given update was recorded in the transaction. */
    private int recordingIndexOf(@NonNull final SystemFileUpdates.ResyncTarget update) {
        for (int i = 0; i < pendingResyncs.size(); i++) {
            if (pendingResyncs.get(i).target() == update) {
                return i;
            }
        }
        throw new IllegalStateException("Update " + update + " is not pending");
    }

    /**
     * Groups the pending updates by facility, each group in recording order, preserving the order in which the
     * facilities were first updated.
     */
    private Map<SystemFileUpdates.ResyncTarget.Facility, List<SystemFileUpdates.ResyncTarget>>
            pendingUpdatesByFacility() {
        final Map<SystemFileUpdates.ResyncTarget.Facility, List<SystemFileUpdates.ResyncTarget>> byFacility =
                new LinkedHashMap<>();
        for (final var pending : pendingResyncs) {
            byFacility
                    .computeIfAbsent(pending.target().facility(), k -> new ArrayList<>())
                    .add(pending.target());
        }
        return byFacility;
    }

    /**
     * Returns a target with the backing file and applied contents of the latest update of a facility and the recovery
     * baselines (configuration file ids, prior fee schedule, prior throttle usage, prior writer mode) of the given
     * earlier update.
     */
    private static SystemFileUpdates.ResyncTarget composed(
            @NonNull final SystemFileUpdates.ResyncTarget latest,
            @NonNull final SystemFileUpdates.ResyncTarget baselines) {
        return latest == baselines
                ? latest
                : new SystemFileUpdates.ResyncTarget(
                        latest.facility(),
                        latest.fileId(),
                        latest.appliedContents(),
                        baselines.configContext(),
                        baselines.priorSimpleFees(),
                        baselines.priorThrottleUsage(),
                        baselines.priorWriterMode(),
                        latest.rolledBack() || baselines.rolledBack());
    }

    /**
     * Returns the given targets with every throttle usage baseline replaced by the earliest one among them. When two
     * throttle-affecting updates (network properties and throttle definitions) are recovered together, the later one's
     * baseline was captured after the earlier one's rebuild had already discarded the usage; restoring it would lose
     * that history, so all of them restore the baseline captured before the first update.
     *
     * @param targets the targets, in the order their updates were recorded
     * @return the targets sharing the earliest baseline
     */
    static List<SystemFileUpdates.ResyncTarget> withSharedThrottleBaseline(
            @NonNull final List<SystemFileUpdates.ResyncTarget> targets) {
        ThrottleServiceManager.PreservedThrottleUsage earliest = null;
        for (final var target : targets) {
            if (target.priorThrottleUsage() != null) {
                earliest = target.priorThrottleUsage();
                break;
            }
        }
        if (earliest == null) {
            return targets;
        }
        final List<SystemFileUpdates.ResyncTarget> shared = new ArrayList<>(targets.size());
        for (final var target : targets) {
            shared.add(target.priorThrottleUsage() == null ? target : target.withPriorThrottleUsage(earliest));
        }
        return shared;
    }

    /**
     * Rethrows the given throwable as-is, preserving its exact type and identity, without declaring it. Used to
     * resurface a resync failure captured across iterations (see {@link #resyncPendingFacilities(State, boolean)}), where the
     * failure may be any {@link Throwable} and must not be wrapped or coerced.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyRethrow(@NonNull final Throwable t) throws T {
        throw (T) t;
    }

    /**
     * Tries to the transaction logic for the given dispatch. If the logic fails and
     * throws HandleException, it will rollback the stack and charge the payer for the
     * fees. If it is throttled, it will charge the payer for the fees and return
     * FEE_ONLY as work done. If it catches an unexpected exception, it will charge
     * the payer for the fees and return FEE_ONLY as work done.
     *
     * @param dispatch the dispatch to be processed
     * @param validation the due diligence report for the dispatch
     * @param fees the fees charged to the payer
     */
    private void tryHandle(
            @NonNull final Dispatch dispatch,
            @NonNull final FeeCharging.Validation validation,
            @NonNull final Fees fees,
            @Nullable final HollowAccountCompletions.Details details,
            final boolean blockFull) {
        final var functionality = dispatch.txnInfo().functionality();
        boolean success = false;
        try {
            if (blockFull) {
                throw ThrottleException.newNativeThrottleException();
            }
            dispatchUsageManager.screenForCapacity(dispatch);
            dispatcher.dispatchHandle(dispatch.handleContext());
            dispatch.streamBuilder().status(SUCCESS);
            if (functionality == ETHEREUM_TRANSACTION) {
                final boolean refundsEnabled =
                        dispatch.config().getConfigData(ContractsConfig.class).evmEthTransactionZeroHapiFeesEnabled();
                if (refundsEnabled) {
                    dispatch.feeChargingOrElse(appFeeCharging).refund(dispatch, fees);
                }
            }
            handleSystemUpdates(dispatch);
            success = true;
        } catch (HandleException e) {
            if (isClprSubmitBundle(dispatch)) {
                logger.debug(
                        "[CLPR-DISPATCH] HandleException txId={} status={} message={}",
                        dispatch.txnInfo().transactionID(),
                        e.getStatus(),
                        e.getMessage());
            }
            final var feeCharging = dispatch.feeChargingOrElse(appFeeCharging);
            feeCharging.rollback();
            rollback(e.getStatus(), dispatch.stack(), dispatch.streamBuilder(), dispatch.feeAccumulator());
            recoverRolledBackTargets(dispatch.stack());
            chargePayer(dispatch, validation, false);
            e.maybeReplay(feeCharging.customized(dispatch), dispatch.handleContext());
        } catch (ThrottleException e) {
            if (isClprSubmitBundle(dispatch)) {
                logger.debug(
                        "[CLPR-DISPATCH] throttled txId={} status={} message={}",
                        dispatch.txnInfo().transactionID(),
                        e.getStatus(),
                        e.getMessage());
            }
            workflowMetrics.incrementThrottled(functionality);
            rollbackAndRechargeFee(dispatch, validation, e.getStatus());
            recoverRolledBackTargets(dispatch.stack());
            if (functionality == ETHEREUM_TRANSACTION) {
                ethereumTransactionHandler.handleThrottled(dispatch.handleContext());
            }
        } catch (Exception e) {
            if (isClprSubmitBundle(dispatch)) {
                logger.error(
                        "[CLPR-DISPATCH] unexpected exception txId={} category={} payer={}",
                        dispatch.txnInfo().transactionID(),
                        dispatch.txnCategory(),
                        dispatch.payerId(),
                        e);
            }
            logger.error("{} - exception thrown while handling dispatch", ALERT_MESSAGE, e);
            rollbackAndRechargeFee(dispatch, validation, FAIL_INVALID);
            recoverRolledBackTargets(dispatch.stack());
        }
        if (!success && details != null) {
            details.replay(dispatch.handleContext()::dispatch);
        }
    }

    /**
     * Handles the system updates for the dispatch. It will notify the responsible system file update facility if
     * any system file was uploaded. It will also notify if the platform state was updated.
     *
     * @param dispatch the dispatch to be processed
     */
    private void handleSystemUpdates(final Dispatch dispatch) {
        // The transaction can still be rolled back or abandoned after this dispatch, and applying an update can fail
        // partway, so first remember what it updates in memory; it is re-derived from the committed state once the
        // transaction completes
        systemFileUpdates
                .resyncTarget(dispatch.stack(), dispatch.txnInfo().txBody())
                // Updates accumulate across a transaction's dispatches, each with the stack that applied it, and are
                // cleared when the root commits
                .ifPresent(target -> {
                    if (isConfigurationFacility(target.facility())) {
                        // Until the outcome is final, block node streaming keeps using the committed configuration
                        systemFileUpdates.holdStreamingConfiguration();
                    }
                    final var pending = new PendingResync(target, dispatch.stack(), false);
                    pendingResyncs.add(pending);
                    // The update follows the dispatch's changes through the savepoints they are committed into, and
                    // is recovered as soon as one of them is rolled back
                    dispatch.stack().anchorToCurrentSavepoint(pending, rollbackObserver);
                });
        if (dispatch.txnInfo().functionality() == NODE_UPDATE) {
            pendingNodeInfoResync = true;
        }

        // Notify responsible facility if system-file was uploaded.
        // Returns SUCCESS if no system-file was uploaded
        final var fileUpdateResult = systemFileUpdates.handleTxBody(
                dispatch.stack(), dispatch.txnInfo().txBody());

        // In case we just changed the exchange rates via 0.0.112 update, reset them now
        dispatch.streamBuilder()
                .exchangeRate(exchangeRateManager.exchangeRates())
                .status(fileUpdateResult);

        // Notify if platform state was updated
        platformStateUpdates.handleTxBody(dispatch.stack(), dispatch.txnInfo().txBody(), dispatch.config());

        if (dispatch.txnInfo().functionality() == NODE_UPDATE) {
            networkInfo.updateFrom(dispatch.stack());
        }
    }

    /**
     * Handles the exception for the dispatch. It will rollback the stack, charge
     * the payer for the fees and return FEE_ONLY as work done.
     *
     * @param dispatch   the dispatch to be processed
     * @param validation the due diligence report for the dispatch
     * @param status     the status to set
     */
    private void rollbackAndRechargeFee(
            @NonNull final Dispatch dispatch,
            @NonNull final FeeCharging.Validation validation,
            @NonNull final ResponseCodeEnum status) {
        dispatch.feeChargingOrElse(appFeeCharging).rollback();
        rollback(status, dispatch.stack(), dispatch.streamBuilder(), dispatch.feeAccumulator());
        chargePayer(dispatch, validation, true);
        dispatchUsageManager.trackFeePayments(dispatch);
    }

    /**
     * Charges the creator for the network fee. This will be called when there is a due diligence failure.
     *
     * @param dispatch   the dispatch to be processed
     * @param validation the validation of the charging scenario
     */
    private void chargeCreator(@NonNull final Dispatch dispatch, @NonNull final FeeCharging.Validation validation) {
        dispatch.streamBuilder().status(validation.errorStatusOrThrow());
        if (isClprSubmitBundle(dispatch)) {
            logger.debug(
                    "[CLPR-DISPATCH] charge creator txId={} creator={} networkFee={} status={}",
                    dispatch.txnInfo().transactionID(),
                    dispatch.creatorInfo().accountId(),
                    dispatch.fees().networkFee(),
                    validation.errorStatusOrThrow());
        }
        // If the transaction is a batch inner transaction, we don't charge the creator
        if (dispatch.category() == BATCH_INNER) {
            return;
        }
        dispatch.feeAccumulator()
                .chargeFee(dispatch.creatorInfo().accountId(), dispatch.fees().networkFee(), null);
    }

    /**
     * Charges the payer for the fees. If the payer is unable to pay the service fee, the service fee
     * will be charged to the creator. If the transaction is a duplicate, the service fee will be waived.
     *
     * @param dispatch the dispatch to be processed
     * @param validation the validation of the charging scenario
     * @param waiveServiceFee whether to waive the service fee from the dispatch
     * @return the fees charged
     */
    private Fees chargePayer(
            @NonNull final Dispatch dispatch,
            @NonNull final FeeCharging.Validation validation,
            final boolean waiveServiceFee) {
        final var fees = dispatch.fees();
        if (fees.nothingToCharge()) {
            return Fees.FREE;
        }
        final var hasWaivedFees = authorizer.hasWaivedFees(
                dispatch.payerId(),
                dispatch.txnInfo().functionality(),
                dispatch.txnInfo().txBody());
        if (hasWaivedFees) {
            return Fees.FREE;
        }
        final var feesToCharge = waiveServiceFee ? fees.withoutServiceComponent() : fees;
        return dispatch.feeChargingOrElse(appFeeCharging).charge(dispatch, validation, feesToCharge);
    }

    /**
     * Rolls back the stack and sets the status of the transaction in case of a failure.
     * @param status the status to set
     * @param stack the save point stack to rollback
     * @param builder the stream builder
     * @param feeAccumulator the fee accumulator to reset after rollback
     */
    private void rollback(
            @NonNull final ResponseCodeEnum status,
            @NonNull final SavepointStackImpl stack,
            @NonNull final StreamBuilder builder,
            @NonNull final FeeAccumulator feeAccumulator) {
        builder.status(status);
        try {
            // Also recovers the in-memory facility updates anchored in the stack
            stack.rollbackFullStack();
        } finally {
            reverseFeeAccumulation(feeAccumulator);
        }
    }

    private static void reverseFeeAccumulation(@NonNull final FeeAccumulator feeAccumulator) {
        // The full-stack rollback reverts the ledger effects of the discarded charge (payer debit and
        // fee collection credit) and the savepoint's node-fee reward counter, but it cannot reach the
        // block-scoped node-payments accumulator. Reverse that accumulation here, before the tracked
        // amounts are cleared, so the payer re-charge that follows does not double-count the node fee.
        feeAccumulator.reverseAccumulatedNodeFees();
        feeAccumulator.resetRefundableFees();
    }

    /**
     * Checks if the transaction has already failed due to an error that can be identified before even performing
     * the dispatch. If it has, it will set the status of the dispatch's record builder and return true.
     * Otherwise, it will return false.
     *
     * @param dispatch   the dispatch to be processed
     * @param validation the due diligence report for the dispatch
     * @return true if the transaction has already failed, false otherwise
     */
    private boolean alreadyFailed(@NonNull final Dispatch dispatch, @NonNull final FeeCharging.Validation validation) {
        if (validation.maybeErrorStatus() != null) {
            if (isClprSubmitBundle(dispatch)) {
                logger.debug(
                        "[CLPR-DISPATCH] skipping handler due to validation error txId={} status={}",
                        dispatch.txnInfo().transactionID(),
                        validation.errorStatusOrThrow());
            }
            dispatch.streamBuilder().status(validation.errorStatusOrThrow());
            return true;
        }
        final var authorizationFailure = maybeAuthorizationFailure(dispatch);
        if (authorizationFailure != null) {
            if (isClprSubmitBundle(dispatch)) {
                logger.debug(
                        "[CLPR-DISPATCH] skipping handler due to authorization failure txId={} status={} "
                                + "category={} payer={}",
                        dispatch.txnInfo().transactionID(),
                        authorizationFailure,
                        dispatch.txnCategory(),
                        dispatch.payerId());
            }
            dispatch.streamBuilder().status(authorizationFailure);
            return true;
        }
        if (failsSignatureVerification(dispatch)) {
            if (isClprSubmitBundle(dispatch)) {
                logger.debug(
                        "[CLPR-DISPATCH] skipping handler due to signature verification failure txId={} "
                                + "requiredKeyRequirements={} hollowAccounts={}",
                        dispatch.txnInfo().transactionID(),
                        dispatch.requiredKeyRequirements().size(),
                        dispatch.hollowAccounts().size());
            }
            dispatch.streamBuilder().status(INVALID_SIGNATURE);
            return true;
        }
        return false;
    }

    private static boolean isClprSubmitBundle(@NonNull final Dispatch dispatch) {
        final var txnInfo = dispatch.txnInfo();
        return txnInfo != null && txnInfo.functionality() == CLPR_SUBMIT_BUNDLE;
    }

    /**
     * Asserts that the dispatch is authorized. If the dispatch is not authorized, it will throw a {@link HandleException}.
     *
     * @param dispatch the dispatch to be processed
     */
    private @Nullable ResponseCodeEnum maybeAuthorizationFailure(@NonNull final Dispatch dispatch) {
        final var function = dispatch.txnInfo().functionality();
        // Handlers are always allowed to dispatch their child step functions
        if (dispatch.txnCategory() == CHILD && HANDLER_STEP_FUNCTIONS.contains(function)) {
            return null;
        }
        if (!authorizer.isAuthorized(dispatch.payerId(), function)) {
            // Node transactions are judged by a different set of rules; any node account can submit
            // any node transaction as long as it is in the allow list
            if (dispatch.txnCategory() == NODE) {
                final var adminConfig = dispatch.config().getConfigData(NetworkAdminConfig.class);
                final var allowList = adminConfig.nodeTransactionsAllowList().functionalitySet();
                if (allowList.contains(function)) {
                    return null;
                }
            }
            return function == SYSTEM_DELETE ? NOT_SUPPORTED : UNAUTHORIZED;
        }
        final var failure = authorizer.hasPrivilegedAuthorization(
                dispatch.payerId(),
                dispatch.txnInfo().functionality(),
                dispatch.txnInfo().txBody());
        return switch (failure) {
            case UNAUTHORIZED -> AUTHORIZATION_FAILED;
            case IMPERMISSIBLE -> ENTITY_NOT_ALLOWED_TO_DELETE;
            default -> null;
        };
    }

    /**
     * Asserts that the signatures are valid. If the signatures are not valid, it will throw a {@link HandleException}.
     *
     * @param dispatch the dispatch to be processed
     */
    private boolean failsSignatureVerification(@NonNull final Dispatch dispatch) {
        for (final var requirement : dispatch.requiredKeyRequirements()) {
            final var verification = dispatch.keyVerifier().verificationFor(requirement);
            if (verification.failed()) {
                return true;
            }
        }
        // If there are any hollow accounts whose signatures need to be verified, verify them
        for (final var hollowAccount : dispatch.hollowAccounts()) {
            final var verification = dispatch.keyVerifier().verificationFor(hollowAccount.alias());
            if (verification.failed()) {
                return true;
            }
        }
        return false;
    }
}
