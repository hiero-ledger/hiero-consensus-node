// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * System contract interface for sending cross-ledger messages via the CLPR service.
 *
 * <p>This is the Java representation of the EVM system contract method:
 * <pre>{@code
 * function sendMessage(
 *     bytes32 channelId,
 *     bytes connectorId,
 *     bytes targetApplication,
 *     bytes messageData
 * ) returns (uint64 messageId)
 * }</pre>
 *
 * <p>Called from EVM execution context, NOT as a HAPI transaction. The system contract
 * implementation will validate the Connector's authorization, compute the running hash,
 * enqueue the message, and return the assigned message ID.
 *
 * <p>Implementation is in CLPR-2.2.
 */
public interface ClprMessageSender {

    /**
     * Enqueue a cross-ledger message on the specified Channel.
     *
     * @param channelId 32-byte Channel ID
     * @param connectorId 32-byte connector identifier bytes passed through from the caller
     * @param targetApplication destination application address on the peer ledger
     * @param messageData opaque application payload
     * @return the assigned message_id (monotonically increasing per Channel)
     */
    long sendMessage(
            @NonNull Bytes channelId,
            @NonNull Bytes connectorId,
            @NonNull Bytes targetApplication,
            @NonNull Bytes messageData);
}
