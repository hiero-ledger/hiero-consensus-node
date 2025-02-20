// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.validation;

import com.hedera.hapi.node.state.roster.Roster;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * A record representing an update to the roster.
 *
 * @param previousRoster the previous roster, if one exists
 * @param currentRoster  the new current roster
 */
public record RosterUpdate(@Nullable Roster previousRoster, @NonNull Roster currentRoster) {}
