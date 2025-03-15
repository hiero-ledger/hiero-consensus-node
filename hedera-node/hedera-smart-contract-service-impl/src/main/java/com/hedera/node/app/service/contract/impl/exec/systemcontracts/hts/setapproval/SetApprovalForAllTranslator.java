// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.setapproval;

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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates setApprovalForAll (including ERC) call to the HTS system contract. There are no special cases for these
 * calls, so the returned {@link Call} is simply an instance of {@link DispatchForResponseCodeHtsCall}.
 */
@Singleton
public class SetApprovalForAllTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /** Selector for setApprovalForAll(address,address,bool) method. */
    public static final SystemContractMethod SET_APPROVAL_FOR_ALL = SystemContractMethod.declare(
                    "setApprovalForAll(address,address,bool)", ReturnTypes.INT)
            .withCategory(Category.APPROVAL);
    /** Selector for setApprovalForAll(address,bool) method. */
    public static final SystemContractMethod ERC721_SET_APPROVAL_FOR_ALL = SystemContractMethod.declare(
                    "setApprovalForAll(address,bool)", ReturnTypes.INT)
            .withVia(CallVia.PROXY)
            .withCategories(Category.ERC721, Category.APPROVAL);

    private final SetApprovalForAllDecoder decoder;

    /**
     * @param decoder the decoder to use for approve calls
     */
    @Inject
    public SetApprovalForAllTranslator(
            final SetApprovalForAllDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(SET_APPROVAL_FOR_ALL, ERC721_SET_APPROVAL_FOR_ALL);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return attempt.isRedirect()
                ? attempt.isMethod(ERC721_SET_APPROVAL_FOR_ALL)
                : attempt.isMethod(SET_APPROVAL_FOR_ALL);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var result = bodyForClassic(attempt);
        return new SetApprovalForAllCall(
                attempt,
                result,
                SetApprovalForAllTranslator::gasRequirement,
                attempt.isSelector(ERC721_SET_APPROVAL_FOR_ALL));
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
        return systemContractGasCalculator.gasRequirement(body, DispatchType.APPROVE, payerId);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(SET_APPROVAL_FOR_ALL)) {
            return decoder.decodeSetApprovalForAll(attempt);
        }
        return decoder.decodeSetApprovalForAllERC(attempt);
    }
}
