// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.EXPIRY;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.EXPIRY_V2;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.TOKEN_KEY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateCommons.updateMethodsSet;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateTokenCommonTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateTranslator extends UpdateTokenCommonTranslator {
    private static final String HEDERA_TOKEN_STRUCT =
            "(string,string,address,string,bool,uint32,bool," + TOKEN_KEY + "[]," + EXPIRY + ")";
    private static final String HEDERA_TOKEN_STRUCT_V2 =
            "(string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY + ")";
    private static final String HEDERA_TOKEN_STRUCT_V3 =
            "(string,string,address,string,bool,int64,bool," + TOKEN_KEY + "[]," + EXPIRY_V2 + ")";
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

    /**
     * @param decoder the decoder to use for token update info calls
     */
    @Inject
    public UpdateTranslator(
            final UpdateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(systemContractMethodRegistry, contractMetrics);

        registerMethods(TOKEN_UPDATE_INFO_FUNCTION_V1, TOKEN_UPDATE_INFO_FUNCTION_V2, TOKEN_UPDATE_INFO_FUNCTION_V3);

        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_V1, decoder::decodeTokenUpdateV1);
        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_V2, decoder::decodeTokenUpdateV2);
        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_V3, decoder::decodeTokenUpdateV3);

        updateMethodsSet.addAll(updateMethodsMap.keySet());
    }
}
