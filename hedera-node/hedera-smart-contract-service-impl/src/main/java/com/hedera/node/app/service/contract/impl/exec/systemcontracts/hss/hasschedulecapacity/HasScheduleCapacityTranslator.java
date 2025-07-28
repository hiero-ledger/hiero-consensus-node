// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.hasschedulecapacity;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.HssCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.math.BigInteger;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code deleteSchedule(address)} calls to the HSS system contract. For details
 * {@see <a href=https://github.com/hiero-ledger/hiero-improvement-proposals/blob/main/HIP/hip-1215.md>HIP-1215</a>}
 */
@Singleton
public class HasScheduleCapacityTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod HAS_SCHEDULE_CAPACITY = SystemContractMethod.declare(
                    "hasScheduleCapacity(uint256,uint256)", ReturnTypes.BOOL)
            .withCategories(Category.SCHEDULE);
    private static final int EXPIRY_SECOND_INDEX = 0;
    private static final int GAS_LIMIT_INDEX = 1;

    @Inject
    public HasScheduleCapacityTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HSS, systemContractMethodRegistry, contractMetrics);
        registerMethods(HAS_SCHEDULE_CAPACITY);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        if (attempt.configuration().getConfigData(ContractsConfig.class).systemContractHasScheduleCapacityEnabled()) {
            return attempt.isMethod(HAS_SCHEDULE_CAPACITY);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Call callFrom(@NonNull final HssCallAttempt attempt) {
        // read parameters
        final var call = HAS_SCHEDULE_CAPACITY.decodeCall(attempt.inputBytes());
        final BigInteger expirySecond = call.get(EXPIRY_SECOND_INDEX);
        final BigInteger gasLimit = call.get(GAS_LIMIT_INDEX);
        // TODO Glib: call hasScheduleCapacity when it will be implemented

        return new HasScheduleCapacityCallStub(attempt);
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
