// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.exec.operations.utils;

import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.accessTrackerFor;
import static com.hedera.node.app.service.contract.impl.exec.utils.FrameUtils.proxyUpdaterFor;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.evm.frame.MessageFrame;

public class OpUtils {
    private OpUtils() {
        throw new UnsupportedOperationException("Utility Class");
    }

    /**
     * If the given {@code frame} has a {@link com.hedera.node.app.service.contract.impl.infra.StorageAccessTracker}
     * in its context, then tracks the given {@code key} and {@code value} as a read in that tracker.
     *
     * @param frame the frame to (maybe) track the read in
     * @param key the key read
     * @param value the value read
     */
    public static void maybeTrackReadIn(
            @NonNull final MessageFrame frame, @NonNull UInt256 key, @NonNull UInt256 value) {
        final var accessTracker = accessTrackerFor(frame);
        if (accessTracker != null) {
            final var worldUpdater = proxyUpdaterFor(frame);
            final var contractId = worldUpdater.getHederaContractId(frame.getRecipientAddress());
            accessTracker.trackIfFirstRead(contractId, key, value);
        }
    }
}
