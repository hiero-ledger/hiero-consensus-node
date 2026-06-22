// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.actions;

/**
 * An action to indicate that the reconnect process has completed
 *
 * @param reconnectStateRound the round number of the state received in the reconnect that just completed
 */
public record ReconnectCompleteAction(long reconnectStateRound) implements PlatformStatusAction {}
