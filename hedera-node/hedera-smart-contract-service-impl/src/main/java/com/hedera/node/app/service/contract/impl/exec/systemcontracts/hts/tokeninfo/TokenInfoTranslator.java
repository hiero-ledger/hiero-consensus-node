// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.tokeninfo;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Modifier;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code getTokenInfo()} calls to the HTS system contract.
 */
@Singleton
public class TokenInfoTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for getTokenInfo(address) method. */
    public static final SystemContractMethod TOKEN_INFO = SystemContractMethod.declare(
                    "getTokenInfo(address)", ReturnTypes.RESPONSE_CODE_TOKEN_INFO)
            .withModifier(Modifier.VIEW)
            .withVariant(Variant.V1)
            .withCategory(Category.TOKEN_QUERY);
    /** Selector for getTokenInfoV2(address) method. */
    public static final SystemContractMethod TOKEN_INFO_V2 = SystemContractMethod.declare(
                    "getTokenInfoV2(address)", ReturnTypes.RESPONSE_CODE_TOKEN_INFO_V2)
            .withModifier(Modifier.VIEW)
            .withVariant(Variant.V2)
            .withCategory(Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public TokenInfoTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(TOKEN_INFO, TOKEN_INFO_V2);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);

        final var v2Enabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractTokenInfoV2Enabled();
        if (attempt.isSelector(TOKEN_INFO)) return Optional.of(TOKEN_INFO);
        if (attempt.isSelectorIfConfigEnabled(v2Enabled, TOKEN_INFO_V2)) return Optional.of(TOKEN_INFO_V2);
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        final var method = attempt.isSelector(TOKEN_INFO) ? TOKEN_INFO : TOKEN_INFO_V2;
        final var args = method.decodeCall(attempt.input().toArrayUnsafe());
        final var token = attempt.linkedToken(fromHeadlongAddress(args.get(0)));
        return new TokenInfoCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.isStaticCall(),
                token,
                attempt.configuration(),
                method.function());
    }
}
