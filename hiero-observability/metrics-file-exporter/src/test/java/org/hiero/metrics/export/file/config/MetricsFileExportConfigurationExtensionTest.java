// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.export.file.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricsFileExportConfigurationExtensionTest {

    @Test
    void testConfigTypes() {
        MetricsFileExportConfigurationExtension extension = new MetricsFileExportConfigurationExtension();

        assertThat(extension.getConfigDataTypes()).isEqualTo(Set.of(MetricsFileExportConfig.class));
    }
}
