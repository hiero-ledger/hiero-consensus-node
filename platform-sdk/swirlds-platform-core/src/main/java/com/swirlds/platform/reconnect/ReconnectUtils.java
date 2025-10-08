// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;

import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.logging.legacy.payload.ReconnectFailurePayload;
import com.swirlds.state.MerkleNodeState;
import java.util.concurrent.ExecutionException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A set of static methods to aid with reconnect
 */
public final class ReconnectUtils {
    private static final Logger logger = LogManager.getLogger(ReconnectUtils.class);

    private ReconnectUtils() {}

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
            throw new StateSyncException(e);
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
