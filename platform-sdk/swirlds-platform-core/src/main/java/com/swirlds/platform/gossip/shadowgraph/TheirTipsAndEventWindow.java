// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip.shadowgraph;

import com.swirlds.common.crypto.Hash;
import com.swirlds.platform.consensus.EventWindow;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * The tips and event window of the sync peer. This is the first thing sent/received during a sync (after protocol
 * negotiation).
 */
public record TheirTipsAndEventWindow(@NonNull EventWindow eventWindow, @NonNull List<Hash> tips) {}
