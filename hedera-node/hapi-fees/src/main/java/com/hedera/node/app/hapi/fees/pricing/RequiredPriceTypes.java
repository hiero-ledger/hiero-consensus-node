// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.SubType;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A helper class that publishes which price {@link SubType}s must be available for each {@link
 * HederaFunctionality}.
 */
public class RequiredPriceTypes {
    RequiredPriceTypes() {
        throw new IllegalStateException("Uninstantiable");
    }

    private static final EnumSet<SubType> ONLY_DEFAULT = EnumSet.of(DEFAULT);
    private static final Map<HederaFunctionality, EnumSet<SubType>> FUNCTIONS_WITH_REQUIRED_SUBTYPES;

    static {
        FUNCTIONS_WITH_REQUIRED_SUBTYPES = new EnumMap<>(HederaFunctionality.class);
        /* The functions with non-DEFAULT prices in hapi-fees/src/main/resources/canonical-prices.json */
        List.of(TOKEN_MINT, TOKEN_BURN, TOKEN_ACCOUNT_WIPE)
                .forEach(function -> FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                        function, EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                TOKEN_CREATE,
                EnumSet.of(
                        TOKEN_FUNGIBLE_COMMON, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                CRYPTO_TRANSFER,
                EnumSet.of(
                        DEFAULT,
                        TOKEN_FUNGIBLE_COMMON,
                        TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE,
                        TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(SCHEDULE_CREATE, EnumSet.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL));
    }

    /**
     * Returns the set of price types that must be available for the given function.
     *
     * @param function the function of interest
     * @return the set of required price for the function
     */
    public static Set<SubType> requiredTypesFor(final HederaFunctionality function) {
        return FUNCTIONS_WITH_REQUIRED_SUBTYPES.getOrDefault(function, ONLY_DEFAULT);
    }
}
