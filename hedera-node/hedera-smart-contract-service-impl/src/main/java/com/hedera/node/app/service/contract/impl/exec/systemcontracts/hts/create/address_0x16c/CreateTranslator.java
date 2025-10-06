// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x16c;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FIXED_FEE_V2;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FRACTIONAL_FEE_V2;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_WITH_METADATA;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.ROYALTY_FEE_V2;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.HtsSystemContract.HTS_16C_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateCommons.createMethodsSet;

import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.CreateCommonTranslator;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Category;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethod.Variant;
import com.hedera.node.app.service.contract.impl.exec.utils.SystemContractMethodRegistry;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Translates {@code createFungibleToken}, {@code createNonFungibleToken},
 * {@code createFungibleTokenWithCustomFees} and {@code createNonFungibleTokenWithCustomFees} calls to the HTS system contract
 * for the 0x16c address.
 */
@Singleton
public class CreateTranslator extends CreateCommonTranslator {

    /** Selector for createFungibleTokenWithCustomFees(HEDERA_TOKEN_WITH_METADATA,int64,int32) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_TOKEN_WITH_METADATA = SystemContractMethod.declare(
                    "createFungibleToken(" + HEDERA_TOKEN_WITH_METADATA + ",int64,int32)", "(int64,address)")
            .withVariants(Variant.FT, Variant.WITH_METADATA)
            .withSupportedAddress(HTS_16C_CONTRACT_ID)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createFungibleTokenWithCustomFees(HEDERA_TOKEN_WITH_METADATA,int64,int32,FIXED_FEE_2[],FRACTIONAL_FEE_2[]) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES =
            SystemContractMethod.declare(
                            "createFungibleTokenWithCustomFees("
                                    + HEDERA_TOKEN_WITH_METADATA
                                    + ",int64,int32,"
                                    + FIXED_FEE_V2
                                    + "[],"
                                    + FRACTIONAL_FEE_V2
                                    + "[])",
                            "(int64,address)")
                    .withVariants(Variant.FT, Variant.WITH_METADATA, Variant.WITH_CUSTOM_FEES)
                    .withSupportedAddress(HTS_16C_CONTRACT_ID)
                    .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createNonFungibleToken(HEDERA_TOKEN_WITH_METADATA) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA = SystemContractMethod.declare(
                    "createNonFungibleToken(" + HEDERA_TOKEN_WITH_METADATA + ")", "(int64,address)")
            .withVariants(Variant.NFT, Variant.WITH_METADATA)
            .withSupportedAddress(HTS_16C_CONTRACT_ID)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createNonFungibleTokenWithCustomFees(HEDERA_TOKEN_WITH_METADATA,FIXED_FEE_2[],FRACTIONAL_FEE_2[]) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES =
            SystemContractMethod.declare(
                            "createNonFungibleTokenWithCustomFees("
                                    + HEDERA_TOKEN_WITH_METADATA
                                    + ","
                                    + FIXED_FEE_V2
                                    + "[],"
                                    + ROYALTY_FEE_V2
                                    + "[])",
                            "(int64,address)")
                    .withVariants(Variant.NFT, Variant.WITH_METADATA, Variant.WITH_CUSTOM_FEES)
                    .withSupportedAddress(HTS_16C_CONTRACT_ID)
                    .withCategory(Category.CREATE_DELETE_TOKEN);

    @Inject
    public CreateTranslator(
            @NonNull final CreateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);
        registerMethods(
                CREATE_FUNGIBLE_TOKEN_WITH_METADATA,
                CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES,
                CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA,
                CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES);

        createMethodsMap.put(CREATE_FUNGIBLE_TOKEN_WITH_METADATA, decoder::decodeCreateFungibleTokenWithMetadata);
        createMethodsMap.put(
                CREATE_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES,
                decoder::decodeCreateFungibleTokenWithMetadataAndCustomFees);
        createMethodsMap.put(CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA, decoder::decodeCreateNonFungibleWithMetadata);
        createMethodsMap.put(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_METADATA_AND_CUSTOM_FEES,
                decoder::decodeCreateNonFungibleWithMetadataAndCustomFees);

        createMethodsSet.addAll(createMethodsMap.keySet());
    }
}
