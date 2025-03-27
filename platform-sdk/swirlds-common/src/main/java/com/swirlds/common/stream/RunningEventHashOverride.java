// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.stream;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.crypto.Hash;

/**
 * A record used to override the running event hash on various components when a new state is loaded (i.e. after a
 * reconnect or a restart).
 *
 * @param legacyRunningEventHash the legacy running event hash of the loaded state, used by the consensus event stream
 * @param isReconnect            whether or not this is a reconnect state
 */
public record RunningEventHashOverride(@NonNull Hash legacyRunningEventHash, boolean isReconnect) {}
