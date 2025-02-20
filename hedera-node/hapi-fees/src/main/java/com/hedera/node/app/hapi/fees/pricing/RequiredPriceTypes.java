// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.SCHEDULE_CREATE_CONTRACT_CALL;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;

import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.SubType;
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
        List.of(TokenMint, TokenBurn, TokenAccountWipe)
                .forEach(function -> FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                        function, EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE)));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                TokenCreate,
                EnumSet.of(
                        TOKEN_FUNGIBLE_COMMON, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(
                CryptoTransfer,
                EnumSet.of(
                        DEFAULT,
                        TOKEN_FUNGIBLE_COMMON,
                        TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE,
                        TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES));
        FUNCTIONS_WITH_REQUIRED_SUBTYPES.put(ScheduleCreate, EnumSet.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL));
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
