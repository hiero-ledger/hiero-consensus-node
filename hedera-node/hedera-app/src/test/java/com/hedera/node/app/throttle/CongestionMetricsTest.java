/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
