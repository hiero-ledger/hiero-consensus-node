/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_WITH_METADATA;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateTokenCommons.updateMethodsSet;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateDecoder;
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
