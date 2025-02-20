// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorized;

import static java.util.Objects.requireNonNull;

import com.esaulpaugh.headlong.abi.Address;
import com.hedera.node.app.service.contract.impl.annotations.ServicesV051;
import com.hedera.node.app.service.contract.impl.exec.FeatureFlags;
import com.hedera.node.app.service.contract.impl.exec.gas.CustomGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.HasCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class IsAuthorizedTranslator extends AbstractCallTranslator<HasCallAttempt> {

    public static final SystemContractMethod IS_AUTHORIZED = SystemContractMethod.declare(
                    "isAuthorized(address,bytes,bytes)", ReturnTypes.RESPONSE_CODE64_BOOL)
            .withCategories(Category.IS_AUTHORIZED);
    private static final int ADDRESS_ARG = 0;
    private static final int MESSAGE_ARG = 1;
    private static final int SIGNATURE_BLOB_ARG = 2;

    private final CustomGasCalculator customGasCalculator;

    @Inject
    public IsAuthorizedTranslator(
            @ServicesV051 @NonNull final FeatureFlags featureFlags,
            @NonNull final CustomGasCalculator customGasCalculator,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);
        requireNonNull(featureFlags, "featureFlags");
        this.customGasCalculator = requireNonNull(customGasCalculator);

        registerMethods(IS_AUTHORIZED);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull HasCallAttempt attempt) {
        requireNonNull(attempt, "attempt");

        final var callEnabled = attempt.configuration()
                .getConfigData(ContractsConfig.class)
                .systemContractAccountServiceIsAuthorizedEnabled();

        if (attempt.isSelectorIfConfigEnabled(callEnabled, IS_AUTHORIZED)) return Optional.of(IS_AUTHORIZED);
        return Optional.empty();
    }

    @Override
    public Call callFrom(@NonNull HasCallAttempt attempt) {
        requireNonNull(attempt, "attempt");

        if (attempt.isSelector(IS_AUTHORIZED)) {

            final var call = IS_AUTHORIZED.decodeCall(attempt.inputBytes());
            final var address = (Address) call.get(ADDRESS_ARG);
            final var message = (byte[]) call.get(MESSAGE_ARG);
            final var signatureBlob = (byte[]) call.get(SIGNATURE_BLOB_ARG);

            return new IsAuthorizedCall(attempt, address, message, signatureBlob, customGasCalculator);
        }
        return null;
    }
}
