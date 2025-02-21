// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.has.isauthorizedraw;

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

/**
 * Translates {@code isAuthorizedRaw()} calls to the HAS system contract. HIP-632.
 */
@Singleton
public class IsAuthorizedRawTranslator extends AbstractCallTranslator<HasCallAttempt> {

    /** Selector for isAuthorizedRaw(address,bytes,bytes) method. */
    public static final SystemContractMethod IS_AUTHORIZED_RAW = SystemContractMethod.declare(
                    "isAuthorizedRaw(address,bytes,bytes)", ReturnTypes.BOOL)
            .withCategories(Category.IS_AUTHORIZED);

    private static final int ADDRESS_ARG = 0;
    private static final int HASH_ARG = 1;
    private static final int SIGNATURE_ARG = 2;

    private final CustomGasCalculator customGasCalculator;

    @Inject
    public IsAuthorizedRawTranslator(
            @ServicesV051 @NonNull final FeatureFlags featureFlags,
            @NonNull final CustomGasCalculator customGasCalculator,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HAS, systemContractMethodRegistry, contractMetrics);
        requireNonNull(featureFlags, "featureFlags");
        this.customGasCalculator = requireNonNull(customGasCalculator);

        registerMethods(IS_AUTHORIZED_RAW);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt, "attempt");

        final var callEnabled = attempt.configuration()
                .getConfigData(ContractsConfig.class)
                .systemContractAccountServiceIsAuthorizedRawEnabled();
        if (attempt.isSelectorIfConfigEnabled(callEnabled, IS_AUTHORIZED_RAW)) return Optional.of(IS_AUTHORIZED_RAW);
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HasCallAttempt attempt) {
        requireNonNull(attempt, "attempt");

        if (attempt.isSelector(IS_AUTHORIZED_RAW)) {

            final var call = IS_AUTHORIZED_RAW.decodeCall(attempt.inputBytes());
            final var address = (Address) call.get(ADDRESS_ARG);
            final var messageHash = (byte[]) call.get(HASH_ARG);
            final var signature = (byte[]) call.get(SIGNATURE_ARG);

            return new IsAuthorizedRawCall(attempt, address, messageHash, signature, customGasCalculator);
        }
        return null;
    }
}
