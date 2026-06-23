// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.actions;

/**
 * An action to indicate that consensus has advanced past a freeze timestamp
 *
 * @param freezeRound the round number of the freeze state
 */
public record FreezePeriodEnteredAction(long freezeRound) implements PlatformStatusAction {}
