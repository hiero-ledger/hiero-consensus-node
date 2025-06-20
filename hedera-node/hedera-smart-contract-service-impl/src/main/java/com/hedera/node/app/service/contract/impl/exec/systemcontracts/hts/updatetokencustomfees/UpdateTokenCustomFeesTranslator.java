// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesDecoder.UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_STRING;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.updatetokencustomfees.UpdateTokenCustomFeesDecoder.UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_STRING;
import static java.util.Objects.requireNonNull;

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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Arrays;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateTokenCustomFeesTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /** Selector for updateFungibleTokenCustomFees(address,(int64,address,bool,bool,address)[],(int64,int64,int64,int64,bool,address)[]) method. */
    public static final SystemContractMethod UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION = SystemContractMethod.declare(
                    UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_STRING, ReturnTypes.INT)
            .withVariants(Variant.FT, Variant.WITH_CUSTOM_FEES)
            .withCategories(Category.UPDATE);
    /** Selector for updateNonFungibleTokenCustomFees(address,(int64,address,bool,bool,address)[],(int64,int64,int64,address,bool,address)[]) method. */
    public static final SystemContractMethod UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION =
            SystemContractMethod.declare(UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_STRING, ReturnTypes.INT)
                    .withVariants(Variant.NFT, Variant.WITH_CUSTOM_FEES)
                    .withCategories(Category.UPDATE);

    private final UpdateTokenCustomFeesDecoder decoder;

    @Inject
    public UpdateTokenCustomFeesTranslator(
            @NonNull final UpdateTokenCustomFeesDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        requireNonNull(decoder);
        this.decoder = decoder;

        registerMethods(UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION, UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        if (!attempt.configuration().getConfigData(ContractsConfig.class).systemContractUpdateCustomFeesEnabled())
            return Optional.empty();
        return attempt.isMethod(
                UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION, UPDATE_NON_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION);
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
        return systemContractGasCalculator.gasRequirement(body, DispatchType.UPDATE_TOKEN_CUSTOM_FEES, payerId);
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, nominalBodyFor(attempt), UpdateTokenCustomFeesTranslator::gasRequirement);
    }

    private TransactionBody nominalBodyFor(@NonNull final HtsCallAttempt attempt) {
        if (Arrays.equals(attempt.selector(), UPDATE_FUNGIBLE_TOKEN_CUSTOM_FEES_FUNCTION.selector())) {
            return decoder.decodeUpdateFungibleTokenCustomFees(attempt);
        } else {
            return decoder.decodeUpdateNonFungibleTokenCustomFees(attempt);
        }
    }
}
