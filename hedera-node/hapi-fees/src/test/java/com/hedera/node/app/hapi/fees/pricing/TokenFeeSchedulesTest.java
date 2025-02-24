// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ACCOUNT_WIPE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_BURN;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FEE_SCHEDULE_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_FREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_PAUSE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNFREEZE_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_UNPAUSE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class TokenFeeSchedulesTest extends FeeSchedulesTestHelper {
    @Test
    void computesExpectedPriceForTokenCreateSubyptes() throws IOException {
        testCanonicalPriceFor(TOKEN_CREATE, TOKEN_FUNGIBLE_COMMON);
        testCanonicalPriceFor(TOKEN_CREATE, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        testCanonicalPriceFor(TOKEN_CREATE, TOKEN_NON_FUNGIBLE_UNIQUE);
        testCanonicalPriceFor(TOKEN_CREATE, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
    }

    @Test
    void computesExpectedPriceForUniqueTokenMint() throws IOException {
        testCanonicalPriceFor(TOKEN_MINT, TOKEN_NON_FUNGIBLE_UNIQUE);
        testCanonicalPriceFor(TOKEN_MINT, TOKEN_FUNGIBLE_COMMON);
    }

    @Test
    void computesExpectedPriceForUniqueTokenWipe() throws IOException {
        testCanonicalPriceFor(TOKEN_ACCOUNT_WIPE, TOKEN_NON_FUNGIBLE_UNIQUE);
        testCanonicalPriceFor(TOKEN_ACCOUNT_WIPE, TOKEN_FUNGIBLE_COMMON);
    }

    @Test
    void computesExpectedPriceForUniqueTokenBurn() throws IOException {
        testCanonicalPriceFor(TOKEN_BURN, TOKEN_NON_FUNGIBLE_UNIQUE);
        testCanonicalPriceFor(TOKEN_BURN, TOKEN_FUNGIBLE_COMMON);
    }

    @Test
    void computesExpectedPriceForFeeScheduleUpdate() throws IOException {
        testCanonicalPriceFor(TOKEN_FEE_SCHEDULE_UPDATE, DEFAULT);
    }

    @Test
    void computesExpectedPriceForTokenFreezeAccount() throws IOException {
        testCanonicalPriceFor(TOKEN_FREEZE_ACCOUNT, DEFAULT);
    }

    @Test
    void computesExpectedPriceForTokenUnfreezeAccount() throws IOException {
        testCanonicalPriceFor(TOKEN_UNFREEZE_ACCOUNT, DEFAULT);
    }

    @Test
    void computesExpectedPriceForTokenPause() throws IOException {
        testCanonicalPriceFor(TOKEN_PAUSE, DEFAULT);
    }

    @Test
    void computesExpectedPriceForTokenUnPause() throws IOException {
        testCanonicalPriceFor(TOKEN_UNPAUSE, DEFAULT);
    }
}
