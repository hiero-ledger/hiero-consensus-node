// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.base.example.ext;

import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;

public record BaseContext(Metrics metrics, Configuration configuration) {}
