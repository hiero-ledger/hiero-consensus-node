// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration of the event intake pipeline that only matters when replaying events reconstructed from the block
 * stream.
 *
 * @param allowUnsignedPcesEvents if true, events read from PCES files that carry an empty signature are accepted by the
 *                                intake pipeline without signature verification. This is intended for replaying events
 *                                reconstructed from the block stream, where the creator's signature is not preserved
 *                                and trust derives from the block proof. Note: This is a TEST ONLY setting. It must
 *                                never be enabled in production.
 */
@ConfigData("event.preconsensus.intake")
public record PcesReplayIntakeConfig(
        @ConfigProperty(defaultValue = "true") boolean allowUnsignedPcesEvents) {}
