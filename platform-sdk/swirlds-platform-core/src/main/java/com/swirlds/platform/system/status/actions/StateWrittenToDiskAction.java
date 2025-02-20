// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.actions;

/**
 * An action to indicate that the state has been written to disk
 *
 * @param round         the round number of the state that was written to disk
 * @param isFreezeState true if the state is a freeze state, false otherwise
 */
public record StateWrittenToDiskAction(long round, boolean isFreezeState) implements PlatformStatusAction {}
