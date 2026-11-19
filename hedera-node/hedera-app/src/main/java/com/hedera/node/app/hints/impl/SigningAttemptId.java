// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Identifies a hinTS signing attempt by the block hash being signed and the construction it signs under, so
 * attempts on the same block hash under adjacent constructions never share a slot.
 *
 * @param blockHash the block hash being signed
 * @param constructionId the id of the hinTS construction the attempt signs under
 */
public record SigningAttemptId(@NonNull Bytes blockHash, long constructionId) {
    public SigningAttemptId {
        requireNonNull(blockHash);
    }
}
