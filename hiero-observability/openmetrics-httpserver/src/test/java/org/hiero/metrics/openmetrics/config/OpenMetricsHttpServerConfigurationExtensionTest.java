// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

public class OpenMetricsHttpServerConfigurationExtensionTest {

    @Test
    void testConfigTypes() {
        OpenMetricsHttpServerConfigurationExtension extension = new OpenMetricsHttpServerConfigurationExtension();

        assertThat(extension.getConfigDataTypes().size()).isEqualTo(1);
        assertThat(extension.getConfigDataTypes()).isEqualTo(Set.of(OpenMetricsHttpServerConfig.class));
    }
}
