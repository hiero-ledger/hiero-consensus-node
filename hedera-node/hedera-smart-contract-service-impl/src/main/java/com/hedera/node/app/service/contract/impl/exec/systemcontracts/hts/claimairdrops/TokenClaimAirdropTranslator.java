// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.claimairdrops;

import static java.util.Objects.requireNonNull;

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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.CallVia;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TokenClaimAirdropTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    public static final SystemContractMethod CLAIM_AIRDROPS = SystemContractMethod.declare(
                    "claimAirdrops((address,address,address,int64)[])", ReturnTypes.INT_64)
            .withCategories(Category.AIRDROP);
    public static final SystemContractMethod HRC_CLAIM_AIRDROP_FT = SystemContractMethod.declare(
                    "claimAirdropFT(address)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withVariant(Variant.FT)
            .withCategories(Category.AIRDROP);
    public static final SystemContractMethod HRC_CLAIM_AIRDROP_NFT = SystemContractMethod.declare(
                    "claimAirdropNFT(address,int64)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withVariant(Variant.NFT)
            .withCategories(Category.AIRDROP);

    private final TokenClaimAirdropDecoder decoder;

    @Inject
    public TokenClaimAirdropTranslator(
            @NonNull final TokenClaimAirdropDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(CLAIM_AIRDROPS, HRC_CLAIM_AIRDROP_FT, HRC_CLAIM_AIRDROP_NFT);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        final var claimAirdropEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractClaimAirdropsEnabled();

        if (!claimAirdropEnabled) return Optional.empty();
        return attempt.isRedirect()
                ? attempt.isMethod(HRC_CLAIM_AIRDROP_FT, HRC_CLAIM_AIRDROP_NFT)
                : attempt.isMethod(CLAIM_AIRDROPS);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt,
                attempt.isSelector(CLAIM_AIRDROPS) ? bodyForClassic(attempt) : bodyForHRC(attempt),
                TokenClaimAirdropTranslator::gasRequirement);
    }

    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.TOKEN_CLAIM_AIRDROP, payerId);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        return decoder.decodeTokenClaimAirdrop(attempt);
    }

    private TransactionBody bodyForHRC(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(HRC_CLAIM_AIRDROP_FT)) {
            return decoder.decodeHrcClaimAirdropFt(attempt);
        } else {
            return decoder.decodeHrcClaimAirdropNft(attempt);
        }
    }
}
