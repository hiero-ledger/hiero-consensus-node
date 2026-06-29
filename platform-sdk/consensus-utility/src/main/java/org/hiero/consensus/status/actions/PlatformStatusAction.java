// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.actions;

import org.hiero.consensus.model.status.PlatformStatus;

/**
 * An interface for platform status actions.
 * <p>
 * A platform status action is an occurrence that has the potential to affect the
 * {@link PlatformStatus PlatformStatus}
 */
public sealed interface PlatformStatusAction
        permits CatastrophicFailureAction,
                DoneReplayingEventsAction,
                FallenBehindAction,
                FreezePeriodEnteredAction,
                ReconnectCompleteAction,
                SelfEventReachedConsensusAction,
                StartedReplayingEventsAction,
                StateWrittenToDiskAction,
                TimeElapsedAction {}
