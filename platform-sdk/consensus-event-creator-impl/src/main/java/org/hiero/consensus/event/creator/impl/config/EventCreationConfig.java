// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for event creation.
 *
 * @param maxCreationRate                     the maximum rate (in hz) that a node can create new events. The maximum
 *                                            rate for the entire network is equal to this value times the number of
 *                                            nodes. A value of 0 means that there is no limit to the number of events
 *                                            that can be created (as long as those events are legal to create).
 * @param creationAttemptRate                 the rate (in hz) at which a node will attempt to create new events. If
 *                                            this value is higher than the max creation rate, it will still be
 *                                            constrained by the max creation rate. This being said, it is recommended
 *                                            to attempt event creation faster than the max creation rate in situations
 *                                            where creation rate is also throttled by the tipset algorithm (i.e. we are
 *                                            waiting for new events to use as parents).
 * @param antiSelfishnessFactor               the lower this number, the more likely it is that a new event will be
 *                                            created that reduces this node's selfishness score. Setting this too low
 *                                            may result in a suboptimal hashgraph topology. Setting this number too
 *                                            high may lead to some nodes being ignored by selfish nodes and unable to
 *                                            cause their events to reach consensus.
 * @param tipsetSnapshotHistorySize           the number of tipsets to keep in the snapshot history. These tipsets are
 *                                            used to compute selfishness scores.
 * @param eventIntakeThrottle                 when the size of the event intake queue equals or exceeds this value, do
 *                                            not permit the creation of new self events.
 * @param maximumPermissibleUnhealthyDuration the maximum amount of time that the system can be unhealthy before event
 *                                            creation stops
 */
@ConfigData("event.creation")
public record EventCreationConfig(
        @ConfigProperty(defaultValue = "20") double maxCreationRate,
        @ConfigProperty(defaultValue = "100") double creationAttemptRate,
        @ConfigProperty(defaultValue = "10") double antiSelfishnessFactor,
        @ConfigProperty(defaultValue = "10") int tipsetSnapshotHistorySize,
        @ConfigProperty(defaultValue = "1024") int eventIntakeThrottle,
        @ConfigProperty(defaultValue = "1s") Duration maximumPermissibleUnhealthyDuration) {}
