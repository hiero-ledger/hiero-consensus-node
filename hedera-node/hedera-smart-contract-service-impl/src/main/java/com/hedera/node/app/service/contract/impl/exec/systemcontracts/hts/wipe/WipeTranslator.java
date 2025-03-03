// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.wipe;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.DispatchForResponseCodeHtsCall.FailureCustomizer.NOOP_CUSTOMIZER;

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
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import com.hedera.node.app.service.contract.impl.hevm.HederaWorldUpdater;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class WipeTranslator extends AbstractCallTranslator<HtsCallAttempt> {

    /** Selector for wipeTokenAccount(address,address,uint32) method. */
    public static final SystemContractMethod WIPE_FUNGIBLE_V1 = SystemContractMethod.declare(
                    "wipeTokenAccount(address,address,uint32)", ReturnTypes.INT)
            .withVariants(Variant.V1, Variant.FT)
            .withCategories(Category.WIPE);
    /** Selector for wipeTokenAccount(address,address,int64) method. */
    public static final SystemContractMethod WIPE_FUNGIBLE_V2 = SystemContractMethod.declare(
                    "wipeTokenAccount(address,address,int64)", ReturnTypes.INT)
            .withVariants(Variant.V2, Variant.FT)
            .withCategories(Category.WIPE);
    /** Selector for wipeTokenAccountNFT(address,address,int64[]) method. */
    public static final SystemContractMethod WIPE_NFT = SystemContractMethod.declare(
                    "wipeTokenAccountNFT(address,address,int64[])", ReturnTypes.INT)
            .withVariant(Variant.NFT)
            .withCategories(Category.WIPE);

    private final WipeDecoder decoder;

    @Inject
    public WipeTranslator(
            @NonNull final WipeDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(WIPE_FUNGIBLE_V1, WIPE_FUNGIBLE_V2, WIPE_NFT);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(WIPE_FUNGIBLE_V1, WIPE_FUNGIBLE_V2, WIPE_NFT);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var body = bodyForClassic(attempt);
        final var isFungibleWipe = body.tokenWipeOrThrow().serialNumbers().isEmpty();
        return new DispatchForResponseCodeHtsCall(
                attempt,
                body,
                isFungibleWipe ? WipeTranslator::fungibleWipeGasRequirement : WipeTranslator::nftWipeGasRequirement,
                NOOP_CUSTOMIZER);
    }

    public static long fungibleWipeGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.WIPE_FUNGIBLE, payerId);
    }

    public static long nftWipeGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.WIPE_NFT, payerId);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(WIPE_FUNGIBLE_V1)) {
            return decoder.decodeWipeFungibleV1(attempt);
        } else if (attempt.isSelector(WIPE_FUNGIBLE_V2)) {
            return decoder.decodeWipeFungibleV2(attempt);
        } else {
            return decoder.decodeWipeNonFungible(attempt);
        }
    }
}
