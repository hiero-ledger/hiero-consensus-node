// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.update.address_0x16c;

import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;

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
public class UpdateNFTsMetadataTranslator extends AbstractCallTranslator<HtsCallAttempt> {
    /** Selector for updateNFTsMetadata(address,int64[],bytes) method. */
    public static final SystemContractMethod UPDATE_NFTs_METADATA = SystemContractMethod.declare(
                    "updateNFTsMetadata(address,int64[],bytes)", ReturnTypes.INT)
            .withVariants(Variant.NFT, Variant.WITH_METADATA)
            .withSupportedAddress(HTS_16C_CONTRACT_ID)
            .withCategories(Category.UPDATE);

    private final UpdateDecoder decoder;

    /**
     * @param decoder the decoder to use for NFTs update  metadata
     */
    @Inject
    public UpdateNFTsMetadataTranslator(
            @NonNull final UpdateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        this.decoder = decoder;

        registerMethods(UPDATE_NFTs_METADATA);
    }

    @Override
    public @NonNull Optional<SystemContractMethod> identifyMethod(@NonNull final HtsCallAttempt attempt) {
        return attempt.isMethod(UPDATE_NFTs_METADATA);
    }

    @Override
    public Call callFrom(@NonNull final HtsCallAttempt attempt) {
        return new DispatchForResponseCodeHtsCall(
                attempt, decoder.decodeUpdateNFTsMetadata(attempt), UpdateNFTsMetadataTranslator::gasRequirement);
    }

    public static long gasRequirement(
            @NonNull final TransactionBody body,
            @NonNull final SystemContractGasCalculator systemContractGasCalculator,
            @NonNull final HederaWorldUpdater.Enhancement enhancement,
            @NonNull final AccountID payerId) {
        return systemContractGasCalculator.gasRequirement(body, DispatchType.TOKEN_UPDATE_NFTS, payerId);
    }
}
