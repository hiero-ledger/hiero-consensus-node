// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Additional API for the CLPR Service beyond its dispatchable handlers.
 * Used by the CLPR system contract to enqueue cross-ledger messages.
 */
public interface ClprServiceApi {

    /**
     * Enqueue a cross-ledger message on the specified Channel.
     *
     * <p>Implements the spec §4.3 algorithm:
     * <ol>
     *   <li>Look up Channel — reject if not found or status != ACTIVE</li>
     *   <li>Lazy config propagation if Channel's config is stale</li>
     *   <li>Look up Connector — reject if not found</li>
     *   <li>Connector authorization (stubbed as always-authorized until CLPR-3.3)</li>
     *   <li>Validate payload size against peer's max_message_payload_bytes</li>
     *   <li>Validate queue depth against max_queue_depth</li>
     *   <li>Construct and enqueue ClprMessage with running hash</li>
     *   <li>Update Channel state</li>
     * </ol>
     *
     * @param channelId 32-byte Channel ID
     * @param connectorId 32-byte derived connector identifier (keccak256-based) passed through from the caller
     * @param targetApplication destination application address on the peer ledger
     * @param sender 20-byte EVM address of the transaction caller (stamped by CLPR, not user-provided)
     * @param messageData opaque application payload
     * @return the assigned message_id (monotonically increasing per Channel)
     * @throws com.hedera.node.app.spi.workflows.HandleException with the appropriate
     *         response code if any validation fails
     */
    long sendMessage(
            @NonNull Bytes channelId,
            @NonNull Bytes connectorId,
            @NonNull Bytes targetApplication,
            @NonNull Bytes sender,
            @NonNull Bytes messageData);
}
