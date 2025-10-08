// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.platform.network.Connection;
import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A set of static methods to aid with reconnect
 */
public final class ReconnectUtils {
    private static final Logger logger = LogManager.getLogger(ReconnectUtils.class);
    /**
     * A value to send to signify the end of a reconnect. A random long value is chosen to minimize the possibility that
     * the stream is misaligned
     */
    private static final long END_RECONNECT_MSG = 0x7747b5bd49693b61L;

    private ReconnectUtils() {}

    /**
     * Send and receive the end reconnect message
     *
     * @param connection the connection to send/receive on
     * @throws IOException if the connection breaks, times out, or the wrong message is received
     */
    static void endReconnectHandshake(@NonNull final Connection connection) throws IOException {
        connection.getDos().writeLong(END_RECONNECT_MSG);
        connection.getDos().flush();
        final long endReconnectMsg = connection.getDis().readLong();
        if (endReconnectMsg != END_RECONNECT_MSG) {
            throw new IOException("Did not receive expected end reconnect message. Expecting %x, Received %x"
                    .formatted(END_RECONNECT_MSG, endReconnectMsg));
        }
    }

    /**
     * Hash the working state to prepare for reconnect
     */
    static void hashStateForReconnect(final MerkleCryptography merkleCryptography, final MerkleNodeState workingState) {
        try {
            merkleCryptography.digestTreeAsync(workingState.getRoot()).get();
        } catch (final ExecutionException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectFailurePayload(
                                    "Error encountered while hashing state for reconnect",
                                    ReconnectFailurePayload.CauseOfFailure.ERROR)
                            .toString(),
                    e);
            throw new ReconnectException(e);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectFailurePayload(
                                    "Interrupted while attempting to hash state",
                                    ReconnectFailurePayload.CauseOfFailure.ERROR)
                            .toString(),
                    e);
        }
    }
}
