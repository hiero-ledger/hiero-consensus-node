// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for preconsensus event intake
 * @param allowUnsignedPcesEvents              if true, allow unsigned events (empty signature) read from PCES files to
 *                                             pass through signature validation without verification. This is intended
 *                                             for replaying events reconstructed from the block stream, where the
 *                                             creator's GossipEvent.signature is not preserved. Trust derives from the
 *                                             block proof, not the per-event signature. Note: This is a TEST ONLY
 *                                             setting. It must never be enabled in production.
 */
@ConfigData("event.preconsensus.intake")
public record EventIntakeConfig(
        @ConfigProperty(defaultValue = "false") boolean allowUnsignedPcesEvents) {}
