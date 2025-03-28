// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_APPROVE_ALLOWANCE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_UPDATE;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hedera.hapi.node.base.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;

import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CryptoFeeSchedulesTest extends FeeSchedulesTestHelper {
    private static final double CREATE_AUTO_ASSOC_ALLOWED_DEVIATION = 0.0001;
    private static final double UPDATE_AUTO_ASSOC_ALLOWED_DEVIATION = 0.01;
    private static final BigDecimal APPROX_AUTO_ASSOC_SLOT_PRICE = BigDecimal.valueOf(0.0018);

    @Test
    void computesExpectedPriceForCryptoTransferSubyptes() throws IOException {
        testCanonicalPriceFor(CRYPTO_TRANSFER, DEFAULT);
        testCanonicalPriceFor(CRYPTO_TRANSFER, TOKEN_FUNGIBLE_COMMON);
        testCanonicalPriceFor(CRYPTO_TRANSFER, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        testCanonicalPriceFor(CRYPTO_TRANSFER, TOKEN_NON_FUNGIBLE_UNIQUE);
        testCanonicalPriceFor(CRYPTO_TRANSFER, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
    }

    @Test
    void computesExpectedPriceForCryptoAllowances() throws IOException {
        testCanonicalPriceFor(CRYPTO_APPROVE_ALLOWANCE, DEFAULT);
    }

    @Test
    void computesExpectedPriceForCryptoCreate() throws IOException {
        testCanonicalPriceFor(CRYPTO_CREATE, DEFAULT);

        for (var numSlots : new int[] {1, 2, 10, 100, 1000}) {
            testExpectedCreatePriceWith(numSlots);
        }
    }

    private void testExpectedCreatePriceWith(int numAutoAssocSlots) throws IOException {
        final var expectedBasePrice =
                canonicalTotalPricesInUsd.get(CRYPTO_CREATE).get(DEFAULT);
        final var scaledUsage = baseOperationUsage.cryptoCreate(numAutoAssocSlots);
        testExpected(expectedBasePrice, scaledUsage, CRYPTO_CREATE, DEFAULT, CREATE_AUTO_ASSOC_ALLOWED_DEVIATION);
    }

    @Test
    void computesExpectedPriceForCryptoUpdate() throws IOException {
        testCanonicalPriceFor(CRYPTO_UPDATE, DEFAULT);

        for (var numSlots : new int[] {1, 2, 10, 100, 1000}) {
            testExpectedUpdatePriceWith(numSlots);
        }
    }

    private void testExpectedUpdatePriceWith(int numAutoAssocSlots) throws IOException {
        final var expectedBasePrice =
                canonicalTotalPricesInUsd.get(CRYPTO_UPDATE).get(DEFAULT);

        final var expectedScaledPrice =
                expectedBasePrice.add(APPROX_AUTO_ASSOC_SLOT_PRICE.multiply(BigDecimal.valueOf(numAutoAssocSlots)));
        final var scaledUsage = baseOperationUsage.cryptoUpdate(numAutoAssocSlots);
        testExpected(expectedScaledPrice, scaledUsage, CRYPTO_UPDATE, DEFAULT, UPDATE_AUTO_ASSOC_ALLOWED_DEVIATION);
    }
}
