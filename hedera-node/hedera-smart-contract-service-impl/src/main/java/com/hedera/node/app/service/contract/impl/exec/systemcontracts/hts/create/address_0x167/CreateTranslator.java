// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.systemcontracts.hts.create.address_0x167;

import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FIXED_FEE;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FIXED_FEE_V2;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FRACTIONAL_FEE;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.FRACTIONAL_FEE_V2;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_V1;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_V2;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.HEDERA_TOKEN_V3;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.ROYALTY_FEE;
import static com.hedera.node.app.hapi.utils.contracts.ParsingConstants.ROYALTY_FEE_V2;
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
 * {@code createFungibleTokenWithCustomFees} and {@code createNonFungibleTokenWithCustomFees} calls to the HTS system contract.
 */
@Singleton
public class CreateTranslator extends CreateCommonTranslator {

    /** Selector for createFungibleToken(HEDERA_TOKEN_V1,uint,uint) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_TOKEN_V1 = SystemContractMethod.declare(
                    "createFungibleToken(" + HEDERA_TOKEN_V1 + ",uint,uint)", "(int64,address)")
            .withVariants(Variant.V1, Variant.FT)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createFungibleToken(HEDERA_TOKEN_V2,uint64,uint32) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_TOKEN_V2 = SystemContractMethod.declare(
                    "createFungibleToken(" + HEDERA_TOKEN_V2 + ",uint64,uint32)", "(int64,address)")
            .withVariants(Variant.V2, Variant.FT)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createFungibleToken(HEDERA_TOKEN_V3,int64,int32) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_TOKEN_V3 = SystemContractMethod.declare(
                    "createFungibleToken(" + HEDERA_TOKEN_V3 + ",int64,int32)", "(int64,address)")
            .withVariants(Variant.V3, Variant.FT)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createFungibleTokenWithCustomFees(HEDERA_TOKEN_V1,uint,uint,FIXED_FEE[],FRACTIONAL_FEE[]) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1 = SystemContractMethod.declare(
                    "createFungibleTokenWithCustomFees("
                            + HEDERA_TOKEN_V1
                            + ",uint,uint,"
                            + FIXED_FEE
                            + "[],"
                            + FRACTIONAL_FEE
                            + "[])",
                    "(int64,address)")
            .withVariants(Variant.V1, Variant.FT, Variant.WITH_CUSTOM_FEES)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createFungibleTokenWithCustomFees(HEDERA_TOKEN_V2,uint64,uint32,FIXED_FEE[],FRACTIONAL_FEE[]) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2 = SystemContractMethod.declare(
                    "createFungibleTokenWithCustomFees("
                            + HEDERA_TOKEN_V2
                            + ",uint64,uint32,"
                            + FIXED_FEE
                            + "[],"
                            + FRACTIONAL_FEE
                            + "[])",
                    "(int64,address)")
            .withVariants(Variant.V2, Variant.FT, Variant.WITH_CUSTOM_FEES)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createFungibleTokenWithCustomFees(HEDERA_TOKEN_V3,int64,int32,FIXED_FEE_V2[],FRACTIONAL_FEE_V2[]) method. */
    public static final SystemContractMethod CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3 = SystemContractMethod.declare(
                    "createFungibleTokenWithCustomFees("
                            + HEDERA_TOKEN_V3
                            + ",int64,int32,"
                            + FIXED_FEE_V2
                            + "[],"
                            + FRACTIONAL_FEE_V2
                            + "[])",
                    "(int64,address)")
            .withVariants(Variant.V3, Variant.FT, Variant.WITH_CUSTOM_FEES)
            .withCategory(Category.CREATE_DELETE_TOKEN);

    /** Selector for createNonFungibleToken(HEDERA_TOKEN_V1) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_V1 = SystemContractMethod.declare(
                    "createNonFungibleToken(" + HEDERA_TOKEN_V1 + ")", "(int64,address)")
            .withVariants(Variant.V1, Variant.NFT)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createNonFungibleToken(HEDERA_TOKEN_V2) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_V2 = SystemContractMethod.declare(
                    "createNonFungibleToken(" + HEDERA_TOKEN_V2 + ")", "(int64,address)")
            .withVariants(Variant.V2, Variant.NFT)
            .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createNonFungibleToken(HEDERA_TOKEN_V3) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_V3 = SystemContractMethod.declare(
                    "createNonFungibleToken(" + HEDERA_TOKEN_V3 + ")", "(int64,address)")
            .withVariants(Variant.V3, Variant.NFT)
            .withCategory(Category.CREATE_DELETE_TOKEN);

    /** Selector for createNonFungibleTokenWithCustomFees(HEDERA_TOKEN_V1,int64,int32,FIXED_FEE[],FRACTIONAL_FEE[]) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1 =
            SystemContractMethod.declare(
                            "createNonFungibleTokenWithCustomFees("
                                    + HEDERA_TOKEN_V1
                                    + ","
                                    + FIXED_FEE
                                    + "[],"
                                    + ROYALTY_FEE
                                    + "[])",
                            "(int64,address)")
                    .withVariants(Variant.V1, Variant.NFT, Variant.WITH_CUSTOM_FEES)
                    .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createNonFungibleTokenWithCustomFees(HEDERA_TOKEN_V2,int64,int32,FIXED_FEE[],FRACTIONAL_FEE[]) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2 =
            SystemContractMethod.declare(
                            "createNonFungibleTokenWithCustomFees("
                                    + HEDERA_TOKEN_V2
                                    + ","
                                    + FIXED_FEE
                                    + "[],"
                                    + ROYALTY_FEE
                                    + "[])",
                            "(int64,address)")
                    .withVariants(Variant.V2, Variant.NFT, Variant.WITH_CUSTOM_FEES)
                    .withCategory(Category.CREATE_DELETE_TOKEN);
    /** Selector for createNonFungibleTokenWithCustomFees(HEDERA_TOKEN_V3,int64,int32,FIXED_FEE_2[],FRACTIONAL_FEE_2[]) method. */
    public static final SystemContractMethod CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3 =
            SystemContractMethod.declare(
                            "createNonFungibleTokenWithCustomFees("
                                    + HEDERA_TOKEN_V3
                                    + ","
                                    + FIXED_FEE_V2
                                    + "[],"
                                    + ROYALTY_FEE_V2
                                    + "[])",
                            "(int64,address)")
                    .withVariants(Variant.V3, Variant.NFT, Variant.WITH_CUSTOM_FEES)
                    .withCategory(Category.CREATE_DELETE_TOKEN);

    /**
     * Constructor for injection.
     * @param decoder the decoder used to decode create calls
     */
    @Inject
    public CreateTranslator(
            final CreateDecoder decoder,
            @NonNull final SystemContractMethodRegistry systemContractMethodRegistry,
            @NonNull final ContractMetrics contractMetrics) {
        super(SystemContractMethod.SystemContract.HTS, systemContractMethodRegistry, contractMetrics);

        registerMethods(
                CREATE_FUNGIBLE_TOKEN_V1,
                CREATE_FUNGIBLE_TOKEN_V2,
                CREATE_FUNGIBLE_TOKEN_V3,
                CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1,
                CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2,
                CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3,
                CREATE_NON_FUNGIBLE_TOKEN_V1,
                CREATE_NON_FUNGIBLE_TOKEN_V2,
                CREATE_NON_FUNGIBLE_TOKEN_V3,
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1,
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2,
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3);

        createMethodsMap.put(CREATE_FUNGIBLE_TOKEN_V1, decoder::decodeCreateFungibleTokenV1);
        createMethodsMap.put(CREATE_FUNGIBLE_TOKEN_V2, decoder::decodeCreateFungibleTokenV2);
        createMethodsMap.put(CREATE_FUNGIBLE_TOKEN_V3, decoder::decodeCreateFungibleTokenV3);
        createMethodsMap.put(CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V1, decoder::decodeCreateFungibleTokenWithCustomFeesV1);
        createMethodsMap.put(CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V2, decoder::decodeCreateFungibleTokenWithCustomFeesV2);
        createMethodsMap.put(CREATE_FUNGIBLE_WITH_CUSTOM_FEES_V3, decoder::decodeCreateFungibleTokenWithCustomFeesV3);
        createMethodsMap.put(CREATE_NON_FUNGIBLE_TOKEN_V1, decoder::decodeCreateNonFungibleV1);
        createMethodsMap.put(CREATE_NON_FUNGIBLE_TOKEN_V2, decoder::decodeCreateNonFungibleV2);
        createMethodsMap.put(CREATE_NON_FUNGIBLE_TOKEN_V3, decoder::decodeCreateNonFungibleV3);
        createMethodsMap.put(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V1, decoder::decodeCreateNonFungibleWithCustomFeesV1);
        createMethodsMap.put(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V2, decoder::decodeCreateNonFungibleWithCustomFeesV2);
        createMethodsMap.put(
                CREATE_NON_FUNGIBLE_TOKEN_WITH_CUSTOM_FEES_V3, decoder::decodeCreateNonFungibleWithCustomFeesV3);

        createMethodsSet.addAll(createMethodsMap.keySet());
    }
}
