// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.crypto;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.platform.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Map;

/**
 * A record representing a roster with the keys and certificates associated with each node.
 *
 * @param roster                the roster
 * @param nodeIdKeysAndCertsMap the keys and certificates associated with each node
 */
public record RosterAndCerts(@NonNull Roster roster, @NonNull Map<NodeId, KeysAndCerts> nodeIdKeysAndCertsMap) {}
