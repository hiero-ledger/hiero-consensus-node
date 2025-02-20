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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class RequiredPriceTypesTest {
    @Test
    void knowsTypedFunctions() {
        // expect:
        assertEquals(
                EnumSet.of(
                        DEFAULT,
                        TOKEN_FUNGIBLE_COMMON,
                        TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE,
                        TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES),
                RequiredPriceTypes.requiredTypesFor(CryptoTransfer));
        assertEquals(
                EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE),
                RequiredPriceTypes.requiredTypesFor(TokenMint));
        assertEquals(
                EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE),
                RequiredPriceTypes.requiredTypesFor(TokenBurn));
        assertEquals(
                EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE),
                RequiredPriceTypes.requiredTypesFor(TokenAccountWipe));
        assertEquals(
                EnumSet.of(
                        TOKEN_FUNGIBLE_COMMON, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES),
                RequiredPriceTypes.requiredTypesFor(TokenCreate));
        assertEquals(
                EnumSet.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL),
                RequiredPriceTypes.requiredTypesFor(ScheduleCreate));
    }

    @Test
    void isUninstantiable() {
        assertThrows(IllegalStateException.class, RequiredPriceTypes::new);
    }
}
