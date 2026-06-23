// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

/**
 * A trigger to indicate that the reconnect process has completed
 *
 * @param reconnectStateRound the round number of the state received in the reconnect that just completed
 */
public record ReconnectCompleteTrigger(long reconnectStateRound) implements StatusMachineTrigger {}
