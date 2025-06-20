// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.rejecttokens;

import com.esaulpaugh.headlong.abi.Function;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import com.hedera.node.app.service.contract.impl.exec.gas.SystemContractGasCalculator;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.AbstractCallTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.Call;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.HtsCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RejectTokensTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    public static final SystemContractMethod TOKEN_REJECT = SystemContractMethod.declare(
                    "rejectTokens(address,address[],(address,int64)[])", ReturnTypes.INT_64)
            .withCategories(Category.REJECT);
    public static final SystemContractMethod HRC_TOKEN_REJECT_FT = SystemContractMethod.declare(
                    "rejectTokenFT()", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withVariant(Variant.FT)
            .withCategories(Category.REJECT);
    public static final SystemContractMethod HRC_TOKEN_REJECT_NFT = SystemContractMethod.declare(
                    "rejectTokenNFTs(int64[])", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withVariant(Variant.NFT)
            .withCategories(Category.REJECT);

    private final RejectTokensDecoder decoder;
    private final Map<Function, DispatchGasCalculator> gasCalculators = new HashMap<>();

    @Inject
    public RejectTokensTranslator(
            @NonNull final RejectTokensDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(TOKEN_REJECT, HRC_TOKEN_REJECT_FT, HRC_TOKEN_REJECT_NFT);

        gasCalculators.put(TOKEN_REJECT.function(), RejectTokensTranslator::gasRequirement);
        gasCalculators.put(HRC_TOKEN_REJECT_FT.function(), RejectTokensTranslator::gasRequirementHRCFungible);
        gasCalculators.put(HRC_TOKEN_REJECT_NFT.function(), RejectTokensTranslator::gasRequirementHRCNft);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        final var rejectEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractRejectTokensEnabled();

        if (!rejectEnabled) return Optional.empty();
        return attempt.isRedirect()
                ? attempt.isMethod(HRC_TOKEN_REJECT_FT, HRC_TOKEN_REJECT_NFT)
                : attempt.isMethod(TOKEN_REJECT);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var gasRequirement = gasCalculators.entrySet().stream()
                .filter(entry -> attempt.isSelector(entry.getKey()))
                .map(Entry::getValue)
                .findFirst();
        return new DispatchForResponseCodeHtsCall(attempt, bodyFor(attempt), gasRequirement.get());
    }

    public static long gasRequirementHRCFungible(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.TOKEN_REJECT_FT, payerId);
    }

    public static long gasRequirementHRCNft(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.TOKEN_REJECT_NFT, payerId);
    }

    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        final var accumulatedCanonicalPricing = body.tokenReject().rejections().stream()
                .map(rejection -> {
                    if (rejection.hasFungibleToken()) {
                        return systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TOKEN_REJECT_FT);
                    } else {
                        return systemContractGasCalculator.canonicalPriceInTinycents(DispatchType.TOKEN_REJECT_NFT);
                    }
                })
                .reduce(0L, Long::sum);
        return systemContractGasCalculator.gasRequirementWithTinycents(body, payerId, accumulatedCanonicalPricing);
    }

    private TransactionBody bodyFor(@NonNull HtsCallAttempt attempt) {
        return attempt.isSelector(TOKEN_REJECT) ? bodyForClassic(attempt) : bodyForHRC(attempt);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        return decoder.decodeTokenRejects(attempt);
    }

    private TransactionBody bodyForHRC(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(HRC_TOKEN_REJECT_FT)) {
            return decoder.decodeHrcTokenRejectFT(attempt);
        } else {
            return decoder.decodeHrcTokenRejectNFT(attempt);
        }
    }
}
