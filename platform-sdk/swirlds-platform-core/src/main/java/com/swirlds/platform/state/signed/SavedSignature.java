// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import com.swirlds.common.crypto.Signature;
import com.swirlds.common.platform.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A signature for a state hash that was received when this node does not yet have a state with a matching round.
 */
public record SavedSignature(long round, @NonNull NodeId memberId, @NonNull Signature signature) {}
