// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hss.schedulecall;

import com.hedera.hapi.node.base.AccountID;
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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code scheduleCall*} calls to the HSS system contract. For details
 * {@see <a href=https://github.com/hiero-ledger/hiero-improvement-proposals/blob/main/HIP/hip-1215.md>HIP-1215</a>}
 */
@Singleton
public class ScheduleCallTranslator extends AbstractCallTranslator<HssCallAttempt> {

    public static final SystemContractMethod SCHEDULE_CALL = SystemContractMethod.declare(
                    "scheduleCall(address,uint256,uint256,uint64,bytes)", ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withCategories(Category.SCHEDULE);
    public static final SystemContractMethod SCHEDULE_CALL_WITH_PAYER = SystemContractMethod.declare(
                    "scheduleCallWithPayer(address,address,uint256,uint256,uint64,bytes)",
                    ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withCategories(Category.SCHEDULE);
    public static final SystemContractMethod EXECUTE_CALL_ON_PAYER_SIGNATURE = SystemContractMethod.declare(
                    "executeCallOnPayerSignature(address,address,uint256,uint256,uint64,bytes)",
                    ReturnTypes.RESPONSE_CODE_ADDRESS)
            .withCategories(Category.SCHEDULE);

    private final ScheduleCallDecoder decoder;

    @Inject
    public ScheduleCallTranslator(
            @NonNull final ScheduleCallDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HSS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;
        registerMethods(SCHEDULE_CALL, SCHEDULE_CALL_WITH_PAYER, EXECUTE_CALL_ON_PAYER_SIGNATURE);
    }

    @Override
    @NonNull
    public Optional<SystemContractMethod> identifyMethod(@NonNull final HssCallAttempt attempt) {
        if (attempt.configuration().getConfigData(ContractsConfig.class).systemContractScheduleCallEnabled()) {
            return attempt.isMethod(SCHEDULE_CALL, SCHEDULE_CALL_WITH_PAYER, EXECUTE_CALL_ON_PAYER_SIGNATURE);
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Call callFrom(@NonNull final HssCallAttempt attempt) {
        final var keys = attempt.keySetFor();
        final var body = decoder.decodeScheduleCall(attempt, keys);
        return new DispatchForResponseCodeHssCall(
                attempt.enhancement(),
                attempt.systemContractGasCalculator(),
                body.scheduleCreateOrThrow().payerAccountIDOrThrow(),
                body,
                attempt.defaultVerificationStrategy(),
                ScheduleCallTranslator::gasRequirement,
                keys,
                DispatchForResponseCodeHssCall::scheduleCreateResultEncode);
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
        return systemContractGasCalculator.gasRequirement(body, DispatchType.SCHEDULE_CREATE_CONTRACT_CALL, payerId);
    }
}
