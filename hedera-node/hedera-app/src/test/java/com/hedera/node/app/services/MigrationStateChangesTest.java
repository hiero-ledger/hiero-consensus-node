// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.node.app.spi.metrics.StoreMetricsService;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.state.State;
import com.swirlds.state.StateChangeListener;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MigrationStateChangesTest {
    @Test
    void preservesClprSingletonChangesAndCommitBoundariesDuringMigration() {
        final var state = mock(State.class);
        final var tracker = new MigrationStateChanges(
                state, HederaTestConfigBuilder.createConfig(), mock(StoreMetricsService.class));
        final var captor = ArgumentCaptor.forClass(StateChangeListener.class);
        verify(state, times(2)).registerCommitListener(captor.capture());
        for (final var listener : captor.getAllValues()) {
            listener.singletonUpdateChange(LEDGER_CONFIGURATION_STATE_ID, ClprLedgerConfiguration.DEFAULT);
            listener.singletonUpdateChange(ENDPOINT_MANIFEST_STATE_ID, ClprEndpointManifest.DEFAULT);
            listener.singletonUpdateChange(
                    ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID, ClprEndpointManifestConstruction.DEFAULT);
        }
        tracker.trackCommit();
        for (final var listener : captor.getAllValues()) {
            listener.singletonUpdateChange(
                    ENDPOINT_MANIFEST_STATE_ID,
                    ClprEndpointManifest.newBuilder().version(2).build());
        }
        final var changes = tracker.getStateChanges();
        assertThat(changes).hasSize(2);
        assertThat(changes.getFirst().build().stateChanges()).hasSize(3);
        assertThat(changes.getLast().build().stateChanges()).singleElement().satisfies(change -> assertThat(
                        change.singletonUpdateOrThrow()
                                .clprEndpointManifestValue()
                                .version())
                .isEqualTo(2));
        for (final var listener : captor.getAllValues()) {
            verify(state).unregisterCommitListener(listener);
        }
    }
}
