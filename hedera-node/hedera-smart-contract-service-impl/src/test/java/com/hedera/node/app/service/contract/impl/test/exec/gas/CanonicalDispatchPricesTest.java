// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.hapi.fees.pricing.AssetsLoader;
import com.hedera.node.app.service.contract.impl.exec.gas.CanonicalDispatchPrices;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NodeFee;
import org.hiero.hapi.support.fees.NetworkFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CanonicalDispatchPricesTest {
    @Mock
    private AssetsLoader assetsLoader;

    private CanonicalDispatchPrices subject;

    @BeforeEach
    void setUp() {}

    @Test
    void liveAssetsContainPricesForAllDispatchTypes() {
        subject = new CanonicalDispatchPrices(new AssetsLoader());
        for (DispatchType dispatchType : DispatchType.values()) {
            assertDoesNotThrow(() -> subject.canonicalPriceInTinycents(dispatchType), "No price for " + dispatchType);
        }
        // Spot check for TokenAssociate ($0.05)
        final long expectedTokenAssociateTinycentPrice = 5 * 100_000_000;
        assertEquals(expectedTokenAssociateTinycentPrice, subject.canonicalPriceInTinycents(DispatchType.ASSOCIATE));
    }

    @Test
    void propagatesAssetLoadingException() throws IOException {
        given(assetsLoader.loadCanonicalPrices()).willThrow(IOException.class);

        assertThrows(UncheckedIOException.class, () -> new CanonicalDispatchPrices(assetsLoader));
    }

    @Test
    void feeScheduleConstructorUsesBaseFeeForMatchingFunctionality() {
        final long associateBaseFee = 500_000_000L;
        final long cryptoCreateBaseFee = 499_000_000L;
        final var feeSchedule = FeeSchedule.newBuilder()
                .node(NodeFee.newBuilder().baseFee(100_000L).build())
                .network(NetworkFee.newBuilder().multiplier(9).build())
                .services(
                        makeService(
                                "Token",
                                makeServiceFee(HederaFunctionality.TOKEN_ASSOCIATE_TO_ACCOUNT, associateBaseFee)),
                        makeService("Crypto", makeServiceFee(HederaFunctionality.CRYPTO_CREATE, cryptoCreateBaseFee)))
                .build();

        subject = new CanonicalDispatchPrices(feeSchedule);

        assertEquals(associateBaseFee, subject.canonicalPriceInTinycents(DispatchType.ASSOCIATE));
        assertEquals(cryptoCreateBaseFee, subject.canonicalPriceInTinycents(DispatchType.CRYPTO_CREATE));
        // Dispatch type not in schedule throws
        assertThrows(NullPointerException.class, () -> subject.canonicalPriceInTinycents(DispatchType.DELETE));
    }
}
