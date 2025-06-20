// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.airdrops;

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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import com.hedera.node.config.data.ContractsConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class TokenAirdropTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    public static final SystemContractMethod TOKEN_AIRDROP = SystemContractMethod.declare(
                    "airdropTokens((address,(address,int64,bool)[],(address,address,int64,bool)[])[])", "(int32)")
            .withCategories(Category.AIRDROP);

    private final TokenAirdropDecoder decoder;

    @Inject
    public TokenAirdropTranslator(
            @NonNull final TokenAirdropDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        requireNonNull(decoder);
        this.decoder = decoder;

        registerMethods(TOKEN_AIRDROP);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        final var airdropEnabled =
                attempt.configuration().getConfigData(ContractsConfig.class).systemContractAirdropTokensEnabled();
        if (!airdropEnabled) return Optional.empty();
        return attempt.isMethod(TOKEN_AIRDROP);
    }

    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.TOKEN_AIRDROP, payerId);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, decoder.decodeAirdrop(attempt), TokenAirdropTranslator::gasRequirement);
    }
}
