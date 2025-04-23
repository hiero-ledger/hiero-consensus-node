// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.address;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.roster.AddressBook;

/**
 * Signals that a new roster has been selected. This happens once per round regardless of whether or not the new roster
 * is any different that the prior roster.
 *
 * @param effectiveRound    the round in which this roster becomes effective
 * @param addressBook        the new address book
 */
public record RoundAddressBookRecord(long effectiveRound, @NonNull AddressBook addressBook) {}
