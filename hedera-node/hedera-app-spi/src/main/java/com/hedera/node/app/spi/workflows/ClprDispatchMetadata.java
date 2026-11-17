// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Metadata attached to contract child dispatches executed by the native CLPR service.
 *
 * @param senderId the synthetic sender id to use for the dispatched EVM transaction
 * @param senderAddress the synthetic EVM sender address to expose as {@code msg.sender}
 */
public record ClprDispatchMetadata(
        @NonNull AccountID senderId, @NonNull Bytes senderAddress) {
    public ClprDispatchMetadata {
        requireNonNull(senderId);
        requireNonNull(senderAddress);
        if (senderAddress.length() != 20) {
            throw new IllegalArgumentException("CLPR dispatch sender address must be 20 bytes");
        }
    }
}
