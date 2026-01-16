// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.deleteschedule;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.EitherOrVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.scope.SpecificCryptoVerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.DispatchForResponseCodeHssCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code deleteSchedule*} calls to the HSS system contract. For details
 * {@see <a href=https://github.com/hiero-ledger/hiero-improvement-proposals/blob/main/HIP/hip-1215.md>HIP-1215</a>}.
 */
@Singleton
public class DeleteScheduleTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod DELETE_SCHEDULE = SystemContractMethod.declare(
                    "deleteSchedule(address)", ReturnTypes.INT_64)
            .withCategories(Category.SCHEDULE);
    private static final int SCHEDULE_ADDRESS_INDEX = 0;

    public static final SystemContractMethod DELETE_SCHEDULE_PROXY = SystemContractMethod.declare(
                    "deleteSchedule()", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.SCHEDULE);

    @Inject
    public DeleteScheduleTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HSS, systemContractMethodRegistry, contractMetrics);
        registerMethods(DELETE_SCHEDULE, DELETE_SCHEDULE_PROXY);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        if (attempt.configuration().getConfigData(ContractsConfig.class).systemContractScheduleCallEnabled()) {
            return attempt.isMethod(DELETE_SCHEDULE, DELETE_SCHEDULE_PROXY);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Call callFrom(@NonNull final HssCallAttempt attempt) {
        if (isRedirectWithNonContractAdminKey(attempt)) {
            // Use a custom verification strategy that checks the admin key of the redirected schedule
            // if the call is via the proxy and the schedule admin key is not a contract key
            return new DispatchForResponseCodeHssCall(
                    attempt,
                    transactionBodyFor(scheduleIdFor(attempt)),
                    getCustomVerificationStrat(attempt),
                    DeleteScheduleTranslator::gasRequirement,
                    attempt.keySetFor());
        } else {
            return new DispatchForResponseCodeHssCall(
                    attempt,
                    transactionBodyFor(scheduleIdFor(attempt)),
                    DeleteScheduleTranslator::gasRequirement,
                    attempt.keySetFor());
        }
    }

    /**
     * Creates a transaction body for:
     * <br>
     * - {@code deleteSchedule(address)}
     * <br>
     * - {@code proxy deleteSchedule()}
     *
     * @param scheduleId the schedule ID
     * @return the transaction body
     */
    @VisibleForTesting
    public TransactionBody transactionBodyFor(@NonNull final ScheduleID scheduleId) {
        return TransactionBody.newBuilder()
                .scheduleDelete(ScheduleDeleteTransactionBody.newBuilder()
                        .scheduleID(scheduleId)
                        .build())
                .build();
    }

    /**
     * Extracts the schedule ID from a {@code deleteSchedule(address)} call or return the redirect schedule ID if the
     * call via the proxy contract
     *
     * @param attempt the call attempt
     * @return the schedule ID
     */
    @VisibleForTesting
    public ScheduleID scheduleIdFor(@NonNull HssCallAttempt attempt) {
        requireNonNull(attempt);
        if (attempt.isSelector(DELETE_SCHEDULE)) {
            final var call = DELETE_SCHEDULE.decodeCall(attempt.inputBytes());
            final Address scheduleAddress = call.get(SCHEDULE_ADDRESS_INDEX);
            return ConversionUtils.addressToScheduleID(
                    attempt.nativeOperations().entityIdFactory(), scheduleAddress);
        } else if (attempt.isSelector(DELETE_SCHEDULE_PROXY)) {
            return attempt.redirectScheduleId();
        }
        throw new IllegalStateException("Unexpected function selector");
    }

    /**
     * Calculates the gas requirement for a {@code SCHEDULE_DELETE} call.
     *
     * @param body                        the transaction body
     * @param systemContractGasCalculator the gas calculator
     * @param enhancement                 the enhancement
     * @param payerId                     the payer account ID
     * @return the gas requirement
     */
    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.SCHEDULE_DELETE, payerId);
    }

    private static boolean isRedirectWithNonContractAdminKey(@NonNull HssCallAttempt attempt) {
        return attempt.isSelector(DELETE_SCHEDULE_PROXY)
                && attempt.isRedirect()
                && attempt.redirectScheduleTxn() != null
                && attempt.redirectScheduleTxn().adminKey() != null
                && !attempt.redirectScheduleTxn().adminKey().hasContractID()
                && !attempt.redirectScheduleTxn().adminKey().hasDelegatableContractId();
    }

    /**
     * Gets a custom verification strategy that uses either the default contract verification strategy or a specific crypto
     * verification strategy based on the admin key of the redirected schedule transaction.
     *
     * @param attempt the HSS call attempt
     * @return the custom verification strategy
     */
    @NonNull
    private static EitherOrVerificationStrategy getCustomVerificationStrat(@NonNull HssCallAttempt attempt) {
        return new EitherOrVerificationStrategy(
                attempt.defaultVerificationStrategy(),
                new SpecificCryptoVerificationStrategy(
                        attempt.redirectScheduleTxn().adminKeyOrElse(Key.DEFAULT)));
    }
}
