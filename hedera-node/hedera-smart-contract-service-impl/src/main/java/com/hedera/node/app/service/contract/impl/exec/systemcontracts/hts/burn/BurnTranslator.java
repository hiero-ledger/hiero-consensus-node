// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.ReturnTypes.INT64_INT64;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.burn.BurnDecoder.BURN_OUTPUT_FN;
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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translator class for burn calls
 */
@Singleton
public class BurnTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /**
     * Selector for burnToken(address,uint64,int64[]) method.
     */
    public static final SystemContractMethod BURN_TOKEN_V1 = SystemContractMethod.declare(
                    "burnToken(address,uint64,int64[])", INT64_INT64)
            .withVariant(Variant.V1)
            .withCategories(Category.MINT_BURN);
    /**
     * Selector for burnToken(address,int64,int64[]) method.
     */
    public static final SystemContractMethod BURN_TOKEN_V2 = SystemContractMethod.declare(
                    "burnToken(address,int64,int64[])", INT64_INT64)
            .withVariant(Variant.V2)
            .withCategories(Category.MINT_BURN);

    BurnDecoder decoder;

    /**
     * Constructor for injection.
     * @param decoder the decoder to use for decoding burn calls
     */
    @Inject
    public BurnTranslator(
            @NonNull final BurnDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(BURN_TOKEN_V1, BURN_TOKEN_V2);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(BURN_TOKEN_V1, BURN_TOKEN_V2);
    }

    @Override
    public Call callFrom(@NonNull HtsCallAttempt attempt) {
        final var body = bodyForClassic(attempt);
        final var isFungibleMint = body.tokenBurnOrThrow().serialNumbers().isEmpty();
        return new DispatchForResponseCodeHtsCall(
                attempt,
                body,
                isFungibleMint ? BurnTranslator::fungibleBurnGasRequirement : BurnTranslator::nftBurnGasRequirement,
                BURN_OUTPUT_FN);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(BURN_TOKEN_V1)) {
            return decoder.decodeBurn(attempt);
        } else {
            return decoder.decodeBurnV2(attempt);
        }
    }

    /**
     * @param body                          the transaction body to be dispatched
     * @param systemContractGasCalculator   the gas calculator for the system contract
     * @param enhancement                   the enhancement to use
     * @param payerId                       the payer of the transaction
     * @return                              the gas requirement
     */
    public static long fungibleBurnGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.BURN_FUNGIBLE, payerId);
    }

    /**
     * @param body                          the transaction body to be dispatched
     * @param systemContractGasCalculator   the gas calculator for the system contract
     * @param enhancement                   the enhancement to use
     * @param payerId                       the payer of the transaction
     * @return                              the gas requirement
     */
    public static long nftBurnGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.BURN_NFT, payerId);
    }
}
