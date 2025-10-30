// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.openmetrics.config;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

public class OpenMetricsHttpEndpointConfigurationExtensionTest {

    @Test
    public void configTypes() {
        OpenMetricsHttpEndpointConfigurationExtension extension = new OpenMetricsHttpEndpointConfigurationExtension();

        assertThat(extension.getConfigDataTypes().size()).isEqualTo(1);
        assertThat(extension.getConfigDataTypes()).isEqualTo(Set.of(OpenMetricsHttpEndpointConfig.class));
    }
}
