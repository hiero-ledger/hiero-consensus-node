// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A trigger processed by the platform status state machine.
 * <p>
 * A trigger is an occurrence that has the potential to affect the {@link PlatformStatus}.
 */
public sealed interface StatusMachineTrigger
        permits CatastrophicFailureTrigger,
                DoneReplayingEventsTrigger,
                FallenBehindTrigger,
                FreezePeriodEnteredTrigger,
                ReconnectCompleteTrigger,
                SelfEventReachedConsensusTrigger,
                StartedReplayingEventsTrigger,
                StateWrittenToDiskTrigger,
                TimeElapsedTrigger {}
