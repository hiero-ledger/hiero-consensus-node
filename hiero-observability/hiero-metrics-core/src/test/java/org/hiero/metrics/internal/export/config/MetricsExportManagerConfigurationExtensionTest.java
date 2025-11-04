// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.export.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

public class MetricsExportManagerConfigurationExtensionTest {

    @Test
    public void configTypes() {
        MetricsExportManagerConfigurationExtension extension = new MetricsExportManagerConfigurationExtension();

        assertThat(extension.getConfigDataTypes().size()).isEqualTo(1);
        assertThat(extension.getConfigDataTypes()).isEqualTo(Set.of(MetricsExportManagerConfig.class));
    }
}
