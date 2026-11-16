// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.service.clpr.impl.WritableLedgerConfigurationStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WritableLedgerConfigurationStoreTest {
    @ParameterizedTest
    @CsvSource({"0,5,2,5", "8,5,2,8", "5,5,9,9"})
    void preservesHistoricalReplyCreditAcrossUpdates(
            final int priorHighWater, final int priorLimit, final int nextLimit, final int expectedHighWater) {
        final var configState = singleton(LEDGER_CONFIGURATION_STATE_ID, config(priorLimit));
        final var highWaterState = singleton(MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID, new ProtoLong(priorHighWater));
        final var states = MapWritableStates.builder()
                .state(configState)
                .state(highWaterState)
                .build();
        final var subject = new WritableLedgerConfigurationStore(states);

        subject.put(config(nextLimit));

        assertThat(subject.getConfiguration()).isEqualTo(config(nextLimit));
        assertThat(highWaterState.get()).isEqualTo(new ProtoLong(expectedHighWater));
        // Commit and reconstruct the store, as happens across transactions and restarts.
        configState.commit();
        highWaterState.commit();
        final var restored = new WritableLedgerConfigurationStore(states);
        restored.put(config(1));
        assertThat(restored.getMaxQueueDepthHighWater()).isEqualTo(expectedHighWater);
    }

    @Test
    void initializesMissingReserveBeforeReplacingLegacyConfiguration() {
        final var states = MapWritableStates.builder()
                .state(singleton(LEDGER_CONFIGURATION_STATE_ID, config(5)))
                .state(singleton(MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID, (ProtoLong) null))
                .build();
        final var subject = new WritableLedgerConfigurationStore(states);

        subject.put(config(2));

        assertThat(subject.getMaxQueueDepthHighWater()).isEqualTo(5);
    }

    @Test
    void localReserveDoesNotChangePeerConfigurationEncoding() throws Exception {
        final var states = MapWritableStates.builder()
                .state(singleton(LEDGER_CONFIGURATION_STATE_ID, config(5)))
                .state(singleton(MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID, new ProtoLong(500)))
                .build();
        final var subject = new WritableLedgerConfigurationStore(states);
        subject.put(config(2));

        final var encoded = ClprLedgerConfiguration.PROTOBUF.toBytes(subject.getConfiguration());
        // The pre-change configuration schema: field 5 (throttles), containing field 4 (queue depth).
        assertThat(encoded).isEqualTo(Bytes.fromHex("2a022002"));
        assertThat(ClprLedgerConfiguration.PROTOBUF.parseStrict(encoded)).isEqualTo(config(2));
        assertThat(subject.getMaxQueueDepthHighWater()).isEqualTo(500);
    }

    private static ClprLedgerConfiguration config(final int limit) {
        return ClprLedgerConfiguration.newBuilder()
                .throttles(ClprThrottles.newBuilder().maxQueueDepth(limit))
                .build();
    }

    private static <T> FunctionWritableSingletonState<T> singleton(final int id, final T initial) {
        final var value = new AtomicReference<>(initial);
        return new FunctionWritableSingletonState<>(id, "CLPR_" + id, value::get, value::set);
    }
}
