// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration values that govern behavior of the protocols that execute between pairs of nodes.
 *
 * @param tolerateMismatchedVersion   If true, a node will tolerate peers with a different software version. If false,
 *                                    connections to peers with different software versions will be severed.
 * @param tolerateMismatchedEpochHash If true, a node will tolerate peers with a different epoch hash. If false,
 *                                    connections to peers with different epoch hashes will be severed.
 * @param rpcGossip                   Use new messaged based protocol for gossip, instead of old block-the-connection
 *                                    style
 */
@ConfigData("protocol")
public record ProtocolConfig(
        @ConfigProperty(defaultValue = "false") boolean tolerateMismatchedVersion,
        @ConfigProperty(defaultValue = "false") boolean tolerateMismatchedEpochHash,
        @ConfigProperty(defaultValue = "true") boolean rpcGossip) {}
