// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import org.hiero.consensus.model.crypto.Hash;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * The tips and event window of the sync peer. This is the first thing sent/received during a sync (after protocol
 * negotiation).
 */
public record TheirTipsAndEventWindow(@NonNull EventWindow eventWindow, @NonNull List<Hash> tips) {}
