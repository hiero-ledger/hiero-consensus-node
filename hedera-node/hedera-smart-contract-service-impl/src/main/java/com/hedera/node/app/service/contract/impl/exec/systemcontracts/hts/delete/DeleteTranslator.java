// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.delete;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.app.service.contract.impl.utils.ConversionUtils;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code delete} calls to the HTS system contract.
 */
@Singleton
public class DeleteTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for deleteToken(address) method. */
    public static final SystemContractMethod DELETE_TOKEN = SystemContractMethod.declare(
                    "deleteToken(address)", ReturnTypes.INT)
            .withCategories(Category.CREATE_DELETE_TOKEN);

    /**
     * Default constructor to delete.
     */
    @Inject
    public DeleteTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(DELETE_TOKEN);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(DELETE_TOKEN);
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(attempt, bodyForClassic(attempt), DeleteTranslator::gasRequirement);
    }

    /**
     * @param body                          the transaction body to be dispatched
     * @param systemContractGasCalculator   the gas calculator for the system contract
     * @param enhancement                   the enhancement to use
     * @param payerId                       the payer of the transaction
     * @return the required gas
     */
    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.DELETE, payerId);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        final var call = DELETE_TOKEN.decodeCall(attempt.inputBytes());
        final var token = ConversionUtils.asTokenId(attempt.nativeOperations().entityIdFactory(), call.get(0));
        return TransactionBody.newBuilder()
                .tokenDeletion(
                        TokenDeleteTransactionBody.newBuilder().token(token).build())
                .build();
    }
}
