// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.clpr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClprSyncMetricsTest {

    @Test
    @DisplayName("metrics register and record against a metrics system")
    void recordsAgainstMetrics() {
        final var subject = new ClprSyncMetrics(new NoOpMetrics());

        assertThatCode(() -> {
                    subject.registerSlotGauges(() -> 1, () -> 2);
                    subject.channelActivated();
                    subject.syncQueued();
                    subject.syncFailed();
                })
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no-op metrics record nothing and never fail")
    void noopRecordsNothing() {
        final var subject = ClprSyncMetrics.noop();

        assertThat(subject).isNotNull();
        assertThatCode(() -> {
                    subject.registerSlotGauges(() -> 1, () -> 2);
                    subject.channelActivated();
                    subject.syncQueued();
                    subject.syncFailed();
                })
                .doesNotThrowAnyException();
    }
}
