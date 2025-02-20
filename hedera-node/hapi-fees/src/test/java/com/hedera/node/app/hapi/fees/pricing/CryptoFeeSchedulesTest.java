// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoApproveAllowance;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES;

import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CryptoFeeSchedulesTest extends FeeSchedulesTestHelper {
    private static final double CREATE_AUTO_ASSOC_ALLOWED_DEVIATION = 0.0001;
    private static final double UPDATE_AUTO_ASSOC_ALLOWED_DEVIATION = 0.01;
    private static final BigDecimal APPROX_AUTO_ASSOC_SLOT_PRICE = BigDecimal.valueOf(0.0018);

    @Test
    void computesExpectedPriceForCryptoTransferSubyptes() throws IOException {
        testCanonicalPriceFor(CryptoTransfer, DEFAULT);
        testCanonicalPriceFor(CryptoTransfer, TOKEN_FUNGIBLE_COMMON);
        testCanonicalPriceFor(CryptoTransfer, TOKEN_FUNGIBLE_COMMON_WITH_CUSTOM_FEES);
        testCanonicalPriceFor(CryptoTransfer, TOKEN_NON_FUNGIBLE_UNIQUE);
        testCanonicalPriceFor(CryptoTransfer, TOKEN_NON_FUNGIBLE_UNIQUE_WITH_CUSTOM_FEES);
    }

    @Test
    void computesExpectedPriceForCryptoAllowances() throws IOException {
        testCanonicalPriceFor(CryptoApproveAllowance, DEFAULT);
    }

    @Test
    void computesExpectedPriceForCryptoCreate() throws IOException {
        testCanonicalPriceFor(CryptoCreate, DEFAULT);

        for (var numSlots : new int[] {1, 2, 10, 100, 1000}) {
            testExpectedCreatePriceWith(numSlots);
        }
    }

    private void testExpectedCreatePriceWith(int numAutoAssocSlots) throws IOException {
        final var expectedBasePrice =
                canonicalTotalPricesInUsd.get(CryptoCreate).get(DEFAULT);
        final var scaledUsage = baseOperationUsage.cryptoCreate(numAutoAssocSlots);
        testExpected(expectedBasePrice, scaledUsage, CryptoCreate, DEFAULT, CREATE_AUTO_ASSOC_ALLOWED_DEVIATION);
    }

    @Test
    void computesExpectedPriceForCryptoUpdate() throws IOException {
        testCanonicalPriceFor(CryptoUpdate, DEFAULT);

        for (var numSlots : new int[] {1, 2, 10, 100, 1000}) {
            testExpectedUpdatePriceWith(numSlots);
        }
    }

    private void testExpectedUpdatePriceWith(int numAutoAssocSlots) throws IOException {
        final var expectedBasePrice =
                canonicalTotalPricesInUsd.get(CryptoUpdate).get(DEFAULT);

        final var expectedScaledPrice =
                expectedBasePrice.add(APPROX_AUTO_ASSOC_SLOT_PRICE.multiply(BigDecimal.valueOf(numAutoAssocSlots)));
        final var scaledUsage = baseOperationUsage.cryptoUpdate(numAutoAssocSlots);
        testExpected(expectedScaledPrice, scaledUsage, CryptoUpdate, DEFAULT, UPDATE_AUTO_ASSOC_ALLOWED_DEVIATION);
    }
}
