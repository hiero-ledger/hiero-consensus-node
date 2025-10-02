// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x167;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.TOKEN_KEY;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_167_CONTRACT_ID;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.UpdateKeysCommonTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class UpdateKeysTranslator extends UpdateKeysCommonTranslator {
    /**
     * Selector for updateTokenKeys(address, TOKEN_KEY[]) method.
     */
    public static final SystemContractMethod TOKEN_UPDATE_KEYS_FUNCTION = SystemContractMethod.declare(
                    "updateTokenKeys(address," + TOKEN_KEY + "[])", ReturnTypes.INT)
            .withCategories(SystemContractMethod.Category.UPDATE)
            .withSupportedAddress(HTS_167_CONTRACT_ID);

    /**
     * @param decoder the decoder to use for token update keys calls
     */
    @Inject
    public UpdateKeysTranslator(
            @NonNull final UpdateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(decoder, systemContractMethodRegistry, contractMetrics);

        registerMethods(TOKEN_UPDATE_KEYS_FUNCTION);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(TOKEN_UPDATE_KEYS_FUNCTION);
    }
}
