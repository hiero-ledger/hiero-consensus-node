// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.mint.MintDecoder.MINT_OUTPUT_FN;
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
 * Translates {@code mintToken()} calls to the HTS system contract.
 */
@Singleton
public class MintTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for mintToken(address,uint64,bytes[]) method. */
    public static final SystemContractMethod MINT = SystemContractMethod.declare(
                    "mintToken(address,uint64,bytes[])", "(int64,int64,int64[])")
            .withVariant(Variant.V1)
            .withCategories(Category.MINT_BURN);
    /** Selector for mintToken(address,int64,bytes[]) method. */
    public static final SystemContractMethod MINT_V2 = SystemContractMethod.declare(
                    "mintToken(address,int64,bytes[])", "(int64,int64,int64[])")
            .withVariant(Variant.V2)
            .withCategories(Category.MINT_BURN);

    private final MintDecoder decoder;

    /**
     * @param decoder the decoder to use for mint calls
     */
    @Inject
    public MintTranslator(
            @NonNull final MintDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(MINT, MINT_V2);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        requireNonNull(attempt);
        return attempt.isMethod(MINT, MINT_V2);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        final var body = bodyForClassic(attempt);
        final var isFungibleMint = body.tokenMintOrThrow().metadata().isEmpty();
        return new DispatchForResponseCodeHtsCall(
                attempt,
                body,
                isFungibleMint ? MintTranslator::fungibleMintGasRequirement : MintTranslator::nftMintGasRequirement,
                MINT_OUTPUT_FN);
    }

    public static long nftMintGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.MINT_NFT, payerId);
    }

    public static long fungibleMintGasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.MINT_FUNGIBLE, payerId);
    }

    private TransactionBody bodyForClassic(@NonNull final HtsCallAttempt attempt) {
        if (attempt.isSelector(MINT)) {
            return decoder.decodeMint(attempt);
        } else {
            return decoder.decodeMintV2(attempt);
        }
    }
}
