// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.ARRAY_BRACKETS;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.EXPIRY;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.EXPIRY_V2;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_WITH_METADATA;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.TOKEN_KEY;

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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    private static final String UPDATE_TOKEN_INFO_STRING = "updateTokenInfo(address,";
    private static final String HEDERA_TOKEN_STRUCT =
            "(string,string,address,string,bool,uint32,bool," + TOKEN_KEY + ARRAY_BRACKETS + "," + EXPIRY + ")";
    private static final String HEDERA_TOKEN_STRUCT_V2 =
            "(string,string,address,string,bool,int64,bool," + TOKEN_KEY + ARRAY_BRACKETS + "," + EXPIRY + ")";
    private static final String HEDERA_TOKEN_STRUCT_V3 =
            "(string,string,address,string,bool,int64,bool," + TOKEN_KEY + ARRAY_BRACKETS + "," + EXPIRY_V2 + ")";
    /** Selector for updateTokenInfo(address, HEDERA_TOKEN_STRUCT) method. */
    public static final SystemContractMethod TOKEN_UPDATE_INFO_FUNCTION_V1 = SystemContractMethod.declare(
                    UPDATE_TOKEN_INFO_STRING + HEDERA_TOKEN_STRUCT + ")", ReturnTypes.INT)
            .withVariant(Variant.V1)
            .withCategories(Category.UPDATE);
    /** Selector for updateTokenInfo(address, HEDERA_TOKEN_STRUCT_V2) method. */
    public static final SystemContractMethod TOKEN_UPDATE_INFO_FUNCTION_V2 = SystemContractMethod.declare(
                    UPDATE_TOKEN_INFO_STRING + HEDERA_TOKEN_STRUCT_V2 + ")", ReturnTypes.INT)
            .withVariant(Variant.V2)
            .withCategories(Category.UPDATE);
    /** Selector for updateTokenInfo(address, HEDERA_TOKEN_STRUCT_V3) method. */
    public static final SystemContractMethod TOKEN_UPDATE_INFO_FUNCTION_V3 = SystemContractMethod.declare(
                    UPDATE_TOKEN_INFO_STRING + HEDERA_TOKEN_STRUCT_V3 + ")", ReturnTypes.INT)
            .withVariant(Variant.V3)
            .withCategories(Category.UPDATE);
    /** Selector for updateTokenInfo(address, HEDERA_TOKEN_WITH_METADATA) method. */
    public static final SystemContractMethod TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA = SystemContractMethod.declare(
                    UPDATE_TOKEN_INFO_STRING + HEDERA_TOKEN_WITH_METADATA + ")", ReturnTypes.INT)
            .withVariant(Variant.WITH_METADATA)
            .withCategories(Category.UPDATE);

    public static final Map<SystemContractMethod, UpdateDecoderFunction> updateMethodsMap = new HashMap<>();

    /**
     * @param decoder the decoder to use for token update info calls
     */
    @Inject
    public UpdateTranslator(
            final UpdateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(
                TOKEN_UPDATE_INFO_FUNCTION_V1,
                TOKEN_UPDATE_INFO_FUNCTION_V2,
                TOKEN_UPDATE_INFO_FUNCTION_V3,
                TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA);

        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_V1, decoder::decodeTokenUpdateV1);
        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_V2, decoder::decodeTokenUpdateV2);
        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_V3, decoder::decodeTokenUpdateV3);
        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA, decoder::decodeTokenUpdateWithMetadata);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        final boolean metadataSupport =
                attempt.configuration().getConfigData(ContractsConfig.class).metadataKeyAndFieldEnabled();

        if (attempt.isSelectorIfConfigEnabled(metadataSupport, TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA))
            return Optional.of(TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA);
        return updateMethodsMap.keySet().stream().filter(attempt::isSelector).findFirst();
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, nominalBodyFor(attempt), UpdateTranslator::gasRequirement, UpdateDecoder.FAILURE_CUSTOMIZER);
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

    private TransactionBody nominalBodyFor(@NonNull final HtsCallAttempt attempt) {
        return updateMethodsMap.entrySet().stream()
                .filter(entry -> attempt.isSelector(entry.getKey()))
                .map(entry -> entry.getValue().decode(attempt))
                .findFirst()
                .orElse(null);
    }
}
