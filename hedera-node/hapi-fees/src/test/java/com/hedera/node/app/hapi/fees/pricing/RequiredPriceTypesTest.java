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
                RequiredPriceTypes.requiredTypesFor(CRYPTO_TRANSFER));
        assertEquals(
                EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE),
                RequiredPriceTypes.requiredTypesFor(TOKEN_MINT));
        assertEquals(
                EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE),
                RequiredPriceTypes.requiredTypesFor(TOKEN_BURN));
        assertEquals(
                EnumSet.of(TOKEN_FUNGIBLE_COMMON, TOKEN_NON_FUNGIBLE_UNIQUE),
                RequiredPriceTypes.requiredTypesFor(TOKEN_ACCOUNT_WIPE));
        assertEquals(
                EnumSet.of(
                        TOKEN_FUNGIBLE_COMMON, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES,
                        TOKEN_NON_FUNGIBLE_UNIQUE, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES),
                RequiredPriceTypes.requiredTypesFor(TOKEN_CREATE));
        assertEquals(
                EnumSet.of(DEFAULT, SCHEDULE_CREATE_CONTRACT_CALL),
                RequiredPriceTypes.requiredTypesFor(SCHEDULE_CREATE));
    }

    @Test
    void isUninstantiable() {
        assertThrows(IllegalStateException.class, RequiredPriceTypes::new);
    }
}
