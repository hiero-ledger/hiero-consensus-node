// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.fees.pricing;

import static com.hedera.hapi.node.base.HederaFunctionality.CONTRACT_UPDATE;
import static com.hedera.hapi.node.base.HederaFunctionality.CRYPTO_TRANSFER;
import static com.hedera.hapi.node.base.HederaFunctionality.SCHEDULE_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_CREATE;
import static com.hedera.hapi.node.base.HederaFunctionality.TOKEN_MINT;
import static com.hedera.hapi.node.base.SubType.DEFAULT;
import static com.hedera.hapi.node.base.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static com.hedera.node.app.hapi.fees.pricing.UsableResource.BPR;
import static com.hedera.node.app.hapi.fees.pricing.UsableResource.CONSTANT;
import static com.hedera.node.app.hapi.fees.pricing.UsableResource.VPT;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AssetsLoaderTest {
    private final AssetsLoader subject = new AssetsLoader();

    @Test
    void canonicalPricesPassSpotChecks() throws IOException {
        // setup:
        final var nftXferPrice = BigDecimal.valueOf(1, 3);
        final var defaultScUpdPrice = BigDecimal.valueOf(26, 3);
        final var defaultTokenAssocPrice = BigDecimal.valueOf(5, 2);

        // given:
        final var prices = subject.loadCanonicalPrices();

        // then:
        assertEquals(nftXferPrice, prices.get(CRYPTO_TRANSFER).get(TOKEN_NON_FUNGIBLE_UNIQUE));
        assertEquals(defaultScUpdPrice, prices.get(CONTRACT_UPDATE).get(DEFAULT));
        assertEquals(
                defaultTokenAssocPrice, prices.get(TOKEN_ASSOCIATE_TO_ACCOUNT).get(DEFAULT));
    }

    @Test
    void capacitiesPassSpotChecks() throws IOException {
        // setup:
        final var constant = BigDecimal.valueOf(1);
        final var vpt = BigDecimal.valueOf(20_000);
        final var bpr = BigDecimal.valueOf(50_000_000);

        // given:
        final var capacities = subject.loadCapacities();

        // then:
        assertEquals(constant, capacities.get(CONSTANT));
        assertEquals(vpt, capacities.get(VPT));
        assertEquals(bpr, capacities.get(BPR));
    }

    @Test
    void weightsPassSpotChecks() throws IOException {
        // setup:
        final var tokenCreate = BigDecimal.valueOf(9, 1);
        final var scheduleCreate = BigDecimal.valueOf(9, 1);
        final var tokenMint = BigDecimal.valueOf(2, 1);

        // given:
        final var weights = subject.loadConstWeights();

        // then:
        assertEquals(tokenCreate, weights.get(TOKEN_CREATE));
        assertEquals(scheduleCreate, weights.get(SCHEDULE_CREATE));
        assertEquals(tokenMint, weights.get(TOKEN_MINT));
    }
}
