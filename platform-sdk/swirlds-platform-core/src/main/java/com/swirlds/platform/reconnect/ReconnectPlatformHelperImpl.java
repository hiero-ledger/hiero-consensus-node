// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.crypto.MerkleCryptography;
import com.swirlds.logging.legacy.payload.ReconnectLoadFailurePayload;
import com.swirlds.platform.state.signed.SignedState;
import com.swirlds.state.MerkleNodeState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.Clearable;

public class ReconnectPlatformHelperImpl implements ReconnectPlatformHelper {

    private static final Logger logger = LogManager.getLogger(ReconnectPlatformHelperImpl.class);

    /** pause gossip for reconnect */
    private final Runnable pauseGossip;
    /** clears all data that is no longer needed since we fell behind */
    private final Clearable clearAll;
    /** supplier of the initial signed state against which to perform a delta based reconnect */
    private final Supplier<MerkleNodeState> workingStateSupplier;
    /** performs the third phase, loading signed state data */
    private final Consumer<SignedState> loadSignedState;

    /** Merkle cryptography */
    private final MerkleCryptography merkleCryptography;

    /**
     *
     * @param pauseGossip callback to pause gossip for reconnect
     * @param clearAll callback to clear all data that is no longer needed since we fell behind
     * @param workingStateSupplier supplier of the initial signed state against which to perform a delta based reconnect
     * @param loadSignedState callback to perform the third phase, loading signed state data
     * @param merkleCryptography Merkle cryptography
     */
    public ReconnectPlatformHelperImpl(
            @NonNull final Runnable pauseGossip,
            @NonNull final Clearable clearAll,
            @NonNull final Supplier<MerkleNodeState> workingStateSupplier,
            @NonNull final Consumer<SignedState> loadSignedState,
            @NonNull final MerkleCryptography merkleCryptography) {
        this.pauseGossip = pauseGossip;
        this.clearAll = clearAll;
        this.workingStateSupplier = workingStateSupplier;
        this.loadSignedState = loadSignedState;
        this.merkleCryptography = merkleCryptography;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepareForReconnect() {
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, stopping gossip");
        pauseGossip.run();
        logger.info(RECONNECT.getMarker(), "Preparing for reconnect, start clearing queues");
        clearAll.clear();
        logger.info(RECONNECT.getMarker(), "Queues have been cleared");
        // Hash the state if it has not yet been hashed
        ReconnectUtils.hashStateForReconnect(merkleCryptography, workingStateSupplier.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean loadSignedState(@NonNull final SignedState signedState) {
        try {
            loadSignedState.accept(signedState);
        } catch (final RuntimeException e) {
            logger.error(
                    EXCEPTION.getMarker(),
                    () -> new ReconnectLoadFailurePayload("Error while loading a received SignedState!").toString(),
                    e);
            // this means we need to start the reconnect process from the beginning
            logger.debug(
                    RECONNECT.getMarker(),
                    "`reloadState` : reloading state, finished, failed, returning `false`: Restart the "
                            + "reconnection process");
            return false;
        }
        return true;
    }
}
