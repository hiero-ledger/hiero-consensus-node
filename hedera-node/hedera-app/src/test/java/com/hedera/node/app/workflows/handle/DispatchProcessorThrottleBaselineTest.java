// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.state.throttles.ThrottleUsageSnapshot;
import com.hedera.node.app.throttle.ThrottleServiceManager;
import com.hedera.node.app.workflows.handle.steps.SystemFileUpdates;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.junit.jupiter.api.Test;

class DispatchProcessorThrottleBaselineTest {
    private static final SystemFileUpdates.ResyncTarget RATES_TARGET = new SystemFileUpdates.ResyncTarget(
            SystemFileUpdates.ResyncTarget.Facility.EXCHANGE_RATES,
            FileID.newBuilder().fileNum(112).build(),
            Bytes.wrap("applied"));

    @Test
    void targetsRecoveredTogetherShareTheEarliestThrottleBaseline() {
        final var earlier = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(1L, null), new ThrottleUsageSnapshot(2L, null), null, null);
        final var later = new ThrottleServiceManager.PreservedThrottleUsage(
                List.of(), new ThrottleUsageSnapshot(0L, null), new ThrottleUsageSnapshot(0L, null), null, null);
        final var properties = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.NETWORK_PROPERTIES,
                FileID.newBuilder().fileNum(121).build(),
                Bytes.wrap("properties"),
                null,
                null,
                earlier,
                null,
                true);
        final var definitions = new SystemFileUpdates.ResyncTarget(
                SystemFileUpdates.ResyncTarget.Facility.THROTTLE_DEFINITIONS,
                FileID.newBuilder().fileNum(123).build(),
                Bytes.wrap("definitions"),
                null,
                null,
                later,
                null,
                true);

        final var shared = DispatchProcessor.withSharedThrottleBaseline(List.of(RATES_TARGET, properties, definitions));

        assertThat(shared.get(0).priorThrottleUsage()).isNull();
        assertThat(shared.get(1).priorThrottleUsage()).isSameAs(earlier);
        assertThat(shared.get(2).priorThrottleUsage()).isSameAs(earlier);
    }
}
