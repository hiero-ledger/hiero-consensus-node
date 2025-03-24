// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.recovery.internal;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.event.PlatformEvent;

/**
 * Used during post-consensus event stream recovery. Stores the state after transactions have been applied to it and
 * the proposed judge for that state.
 * @param state a state that has had transactions applied to it
 * @param judge the proposed judge for the state
 */
public record RecoveredState(@NonNull ReservedSignedState state, @NonNull PlatformEvent judge) {}
