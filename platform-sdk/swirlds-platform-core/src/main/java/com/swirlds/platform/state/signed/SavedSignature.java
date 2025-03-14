// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import com.swirlds.common.crypto.Signature;
import com.swirlds.common.platform.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A signature that was received when there was no state with a matching round.
 */
public record SavedSignature(long round, @NonNull NodeId memberId, @NonNull Signature signature) {}
