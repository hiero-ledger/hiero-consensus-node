// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.cancelairdrops;

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
public class TokenCancelAirdropTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    // Actual signature definition with struct name before flattening
    // cancelAirdrops(PendingAirdrop[])
    public static final SystemContractMethod CANCEL_AIRDROPS = SystemContractMethod.declare(
                    "cancelAirdrops((address,address,address,int64)[])", ReturnTypes.INT_64)
            .withCategories(Category.AIRDROP);
    public static final SystemContractMethod HRC_CANCEL_AIRDROP_FT = SystemContractMethod.declare(
                    "cancelAirdropFT(address)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withVariant(Variant.FT)
            .withCategories(Category.AIRDROP);
    public static final SystemContractMethod HRC_CANCEL_AIRDROP_NFT = SystemContractMethod.declare(
                    "cancelAirdropNFT(address,int64)", ReturnTypes.INT_64)
            .withVia(CallVia.PROXY)
            .withVariant(Variant.NFT)
            .withCategories(Category.AIRDROP);

    private final TokenCancelAirdropDecoder decoder;

    @Inject
    public TokenCancelAirdropTranslator(
            @NonNull final TokenCancelAirdropDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        requireNonNull(decoder);
        this.decoder = decoder;

        registerMethods(CANCEL_AIRDROPS, HRC_CANCEL_AIRDROP_FT, HRC_CANCEL_AIRDROP_NFT);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        final var cancelAirdropEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractCancelAirdropsEnabled();

        if (!cancelAirdropEnabled) return Optional.empty();
        return attempt.isRedirect()
                ? attempt.isMethod(HRC_CANCEL_AIRDROP_FT, HRC_CANCEL_AIRDROP_NFT)
                : attempt.isMethod(CANCEL_AIRDROPS);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, bodyFor(attempt), TokenCancelAirdropTranslator::gasRequirement);
    }

    private TransactionBody bodyFor(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(CANCEL_AIRDROPS)) {
            return decoder.decodeCancelAirdrop(attempt);
        } else if (attempt.isSelector(HRC_CANCEL_AIRDROP_FT)) {
            return decoder.decodeCancelAirdropFT(attempt);
        } else {
            return decoder.decodeCancelAirdropNFT(attempt);
        }
    }

    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.TOKEN_CANCEL_AIRDROP, payerId);
    }
}
