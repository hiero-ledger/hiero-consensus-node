// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.fees.congestion.CongestionMultipliers;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.workflows.TransactionInfo;
import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CongestionMetricsTest {
    @Mock
    private Metrics metrics;

    @Mock
    private CongestionMultipliers congestionMultipliers;

    @Mock
    private LongGauge longGauge;

    @Mock
    private TransactionInfo txnInfo;

    @Mock
    private ReadableStoreFactory storeFactory;

    private CongestionMetrics congestionMetrics;

    @BeforeEach
    void setUp() {
        when(metrics.getOrCreate(any(LongGauge.Config.class))).thenReturn(longGauge);
        congestionMetrics = new CongestionMetrics(metrics, congestionMultipliers);
    }

    @Test
    void testUpdateMultiplier() {
        when(congestionMultipliers.maxCurrentMultiplier(txnInfo, storeFactory)).thenReturn(5L);

        congestionMetrics.updateMultiplier(txnInfo, storeFactory);

        verify(longGauge).set(5L);
    }
}
