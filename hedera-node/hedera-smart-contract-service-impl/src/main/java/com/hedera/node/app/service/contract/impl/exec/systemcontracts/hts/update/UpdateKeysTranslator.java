// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.ARRAY_BRACKETS;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.TOKEN_KEY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateDecoder.FAILURE_CUSTOMIZER;

import com.hedera.hapi.node.base.AccountID;
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
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateKeysTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for updateTokenKeys(address, TOKEN_KEY[]) method. */
    public static final SystemContractMethod TOKEN_UPDATE_KEYS_FUNCTION = SystemContractMethod.declare(
                    "updateTokenKeys(address," + TOKEN_KEY + ARRAY_BRACKETS + ")", ReturnTypes.INT)
            .withCategories(Category.UPDATE);

    private final UpdateDecoder decoder;

    /**
     * @param decoder the decoder to use for token update keys calls
     */
    @Inject
    public UpdateKeysTranslator(
            @NonNull final UpdateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(TOKEN_UPDATE_KEYS_FUNCTION);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(TOKEN_UPDATE_KEYS_FUNCTION);
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt,
                decoder.decodeTokenUpdateKeys(attempt),
                UpdateKeysTranslator::gasRequirement,
                FAILURE_CUSTOMIZER);
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
        return systemContractGasCalculator.gasRequirement(body, DispatchType.UPDATE, payerId);
    }
}
