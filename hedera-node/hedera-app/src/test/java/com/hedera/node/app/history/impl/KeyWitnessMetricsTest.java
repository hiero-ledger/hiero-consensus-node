// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KeyWitnessMetricsTest {
    @Mock
    private Metrics metrics;

    @Mock
    private LongGauge pendingCount;

    @Mock
    private LongGauge oldestAge;

    @Test
    void registersAndUpdatesPendingCountAndAgeGauges() {
        final var configs = ArgumentCaptor.forClass(LongGauge.Config.class);
        when(metrics.getOrCreate(configs.capture())).thenReturn(pendingCount, oldestAge);
        final var subject = new KeyWitnessMetrics(metrics);

        assertEquals(
                "hintsKeyWitnessPendingCount", configs.getAllValues().get(0).getName());
        assertEquals(
                "hintsKeyWitnessOldestPendingAgeSeconds",
                configs.getAllValues().get(1).getName());
        subject.update(3, 86_400);

        verify(pendingCount).set(3);
        verify(oldestAge).set(86_400);
    }
}
