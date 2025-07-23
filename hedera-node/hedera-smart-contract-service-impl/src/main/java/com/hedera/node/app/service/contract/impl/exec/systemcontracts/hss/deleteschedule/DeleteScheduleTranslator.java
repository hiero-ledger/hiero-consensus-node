// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.deleteschedule;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.explicitFromHeadlong;
import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.google.common.annotations.VisibleForTesting;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.scheduled.ScheduleDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
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
 * {@see <a href=https://github.com/hiero-ledger/hiero-improvement-proposals/blob/main/HIP/hip-1215.md>HIP-1215</a>}
 */
@Singleton
public class DeleteScheduleTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod DELETE_SCHEDULED = SystemContractMethod.declare(
                    "deleteSchedule(address)", ReturnTypes.INT_64)
            .withCategories(Category.SCHEDULE);
    private static final int SCHEDULE_ID_INDEX = 0;

    public static final SystemContractMethod DELETE_SCHEDULED_PROXY = SystemContractMethod.declare(
                    "deleteSchedule()", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withCategories(Category.SCHEDULE);

    @Inject
    public DeleteScheduleTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HSS, systemContractMethodRegistry, contractMetrics);
        registerMethods(DELETE_SCHEDULED, DELETE_SCHEDULED_PROXY);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        if (attempt.configuration().getConfigData(ContractsConfig.class).systemContractDeleteScheduleEnabled()) {
            return attempt.isMethod(DELETE_SCHEDULED, DELETE_SCHEDULED_PROXY);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Call callFrom(@NonNull final HssCallAttempt attempt) {
        // create TransactionBody
        TransactionBody body = TransactionBody.newBuilder()
                // create ScheduleCreateTransactionBody
                .scheduleDelete(ScheduleDeleteTransactionBody.newBuilder()
                        .scheduleID(scheduleIdFor(attempt))
                        .build())
                .build();
        return new DispatchForResponseCodeHssCall(
                attempt, body, DeleteScheduleTranslator::gasRequirement, attempt.keySetFor());
    }

    /**
     * Extracts the schedule ID from a {@code deleteSchedule(address)} call or return the redirect schedule ID if the call
     * via the proxy contract
     *
     * @param attempt the call attempt
     * @return the schedule ID
     */
    @VisibleForTesting
    public ScheduleID scheduleIdFor(@NonNull HssCallAttempt attempt) {
        requireNonNull(attempt);
        if (attempt.isSelector(DELETE_SCHEDULED)) {
            final var call = DELETE_SCHEDULED.decodeCall(attempt.inputBytes());
            final Address scheduleAddress = call.get(SCHEDULE_ID_INDEX);
            final var number = ConversionUtils.numberOfLongZero(explicitFromHeadlong(scheduleAddress));
            return attempt.nativeOperations().entityIdFactory().newScheduleId(number);
        } else if (attempt.isSelector(DELETE_SCHEDULED_PROXY)) {
            return attempt.redirectScheduleId();
        }
        throw new IllegalStateException("Unexpected function selector");
    }

    /**
     * Calculates the gas requirement for a {@code SCHEDULE_CREATE} call.
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
}
