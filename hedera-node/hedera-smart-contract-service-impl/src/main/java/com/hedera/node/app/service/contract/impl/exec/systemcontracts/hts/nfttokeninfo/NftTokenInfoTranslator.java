// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.nfttokeninfo;

import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.fromHeadlongAddress;
import static java.util.Objects.requireNonNull;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class NftTokenInfoTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /** Selector for getNonFungibleTokenInfo(address,int64) method. */
    public static final SystemContractMethod NON_FUNGIBLE_TOKEN_INFO = SystemContractMethod.declare(
                    "getNonFungibleTokenInfo(address,int64)", ReturnTypes.RESPONSE_CODE_NON_FUNGIBLE_TOKEN_INFO)
            .withVariants(Variant.V1, Variant.NFT)
            .withCategory(Category.TOKEN_QUERY);

    /** Selector for getNonFungibleTokenInfoV2(address,int64) method. */
    public static final SystemContractMethod NON_FUNGIBLE_TOKEN_INFO_V2 = SystemContractMethod.declare(
                    "getNonFungibleTokenInfoV2(address,int64)", ReturnTypes.RESPONSE_CODE_NON_FUNGIBLE_TOKEN_INFO_V2)
            .withVariants(Variant.V2, Variant.NFT)
            .withCategory(Category.TOKEN_QUERY);

    /**
     * Default constructor for injection.
     */
    @Inject
    public NftTokenInfoTranslator(
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        // Dagger2
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(NON_FUNGIBLE_TOKEN_INFO, NON_FUNGIBLE_TOKEN_INFO_V2);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        final var v2Enabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractTokenInfoV2Enabled();
        if (attempt.isMethod(NON_FUNGIBLE_TOKEN_INFO).isPresent()) return Optional.of(NON_FUNGIBLE_TOKEN_INFO);
        if (attempt.isSelectorIfConfigEnabled(v2Enabled, NON_FUNGIBLE_TOKEN_INFO_V2))
            return Optional.of(NON_FUNGIBLE_TOKEN_INFO_V2);
        return Optional.empty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        final var method =
                attempt.isSelector(NON_FUNGIBLE_TOKEN_INFO) ? NON_FUNGIBLE_TOKEN_INFO : NON_FUNGIBLE_TOKEN_INFO_V2;
        final var args = method.decodeCall(attempt.input().toArrayUnsafe());
        final var token = attempt.linkedToken(fromHeadlongAddress(args.get(0)));
        return new NftTokenInfoCall(
                attempt.systemContractGasCalculator(),
                attempt.enhancement(),
                attempt.isStaticCall(),
                token,
                args.get(1),
                attempt.configuration(),
                method.function());
    }
}
