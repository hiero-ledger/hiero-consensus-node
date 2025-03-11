// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.gas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

import com.hedera.node.app.hapi.fees.pricing.AssetsLoader;
import com.hedera.node.app.service.contract.impl.exec.gas.CanonicalDispatchPrices;
import com.hedera.node.app.service.contract.impl.exec.gas.DispatchType;
import java.io.IOException;
import java.io.UncheckedIOException;
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
}
