// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_WITH_METADATA;
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

    /** Selector for updateTokenInfo(address, HEDERA_TOKEN_WITH_METADATA) method. */
    public static final SystemContractMethod TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA = SystemContractMethod.declare(
                    UPDATE_TOKEN_INFO_STRING + HEDERA_TOKEN_WITH_METADATA + ")", ReturnTypes.INT)
            .withVariant(Variant.WITH_METADATA)
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

        registerMethods(TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA);

        updateMethodsMap.put(TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA, decoder::decodeTokenUpdateWithMetadata);

        updateMethodsSet.add(TOKEN_UPDATE_INFO_FUNCTION_WITH_METADATA);
    }
}
