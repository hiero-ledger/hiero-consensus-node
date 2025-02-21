// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.simulated.config;

import com.swirlds.common.platform.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.util.Map;

/**
 * Configuration for a simulated network of nodes.
 *
 * @param name        a short description of this configuration
 * @param duration    the amount of time this list of configs will be in effect
 * @param nodeConfigs configurations for each node in the network
 */
public record NetworkConfig(
        @Nullable String name, @NonNull Duration duration, @NonNull Map<NodeId, NodeConfig> nodeConfigs) {}
