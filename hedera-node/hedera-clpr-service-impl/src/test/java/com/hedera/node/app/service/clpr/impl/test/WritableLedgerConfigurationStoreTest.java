// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.node.app.service.clpr.impl.WritableLedgerConfigurationStore;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.test.fixtures.FunctionWritableSingletonState;
import com.swirlds.state.test.fixtures.MapWritableStates;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WritableLedgerConfigurationStoreTest {
    @Test
    void persistsConfigurationWithoutChangingPeerProofEncoding() throws Exception {
        final var configState = singleton(LEDGER_CONFIGURATION_STATE_ID, config(2));
        final var states = MapWritableStates.builder().state(configState).build();
        final var subject = new WritableLedgerConfigurationStore(states);
        subject.put(config(5));
        configState.commit();

        final var restored = new WritableLedgerConfigurationStore(states).getConfiguration();
        final var encoded = ClprLedgerConfiguration.PROTOBUF.toBytes(restored);
        // Existing schema: field 5 (throttles), containing field 4 (queue depth).
        assertThat(encoded).isEqualTo(Bytes.fromHex("2a022005"));
        assertThat(ClprLedgerConfiguration.PROTOBUF.parseStrict(encoded)).isEqualTo(config(5));
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
