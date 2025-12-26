// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.export.extension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.util.Optional;
import org.hiero.metrics.api.export.snapshot.MetricsCollectionSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class PullingMetricsExporterAdapterTest {

    private PullingMetricsExporterAdapter exporter;

    @BeforeEach
    void setUp() {
        exporter = new PullingMetricsExporterAdapter("testExporter");
    }

    @Test
    void testNullName() {
        assertThatThrownBy(() -> new PullingMetricsExporterAdapter(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void testBlankName(String name) {
        assertThatThrownBy(() -> new PullingMetricsExporterAdapter(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void testName() {
        assertThat(exporter.name()).isEqualTo("testExporter");
    }

    @Test
    void testGetSnapshotWhenNotInitialized() {
        assertThat(exporter.getSnapshot()).isEmpty();
    }

    @Test
    void testGetSnapshotAfterSetSnapshotProvider() {
        MetricsCollectionSnapshot snapshot1 = mock(MetricsCollectionSnapshot.class);
        MetricsCollectionSnapshot snapshot2 = mock(MetricsCollectionSnapshot.class);

        exporter.setSnapshotProvider(() -> Optional.of(snapshot1));
        assertThat(exporter.getSnapshot()).contains(snapshot1);

        exporter.setSnapshotProvider(() -> Optional.of(snapshot2));
        assertThat(exporter.getSnapshot()).contains(snapshot2);
    }

    @Test
    void testClose() throws IOException {
        MetricsCollectionSnapshot snapshot = mock(MetricsCollectionSnapshot.class);
        exporter.setSnapshotProvider(() -> Optional.of(snapshot));

        assertThat(exporter.getSnapshot()).contains(snapshot);

        exporter.close();
        assertThat(exporter.getSnapshot()).isEmpty();
    }
}
