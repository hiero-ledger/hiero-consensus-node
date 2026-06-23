// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.triggers;

/**
 * A trigger to indicate that consensus has advanced past a freeze timestamp
 *
 * @param freezeRound the round number of the freeze state
 */
public record FreezePeriodEnteredTrigger(long freezeRound) implements StatusMachineTrigger {}
