// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.hiero.base.crypto.Hashable;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A {@link LearningSynchronizer} with simulated latency.
 *
 * @param <T> the type of a message received from the teacher
 */
public class LaggingLearningSynchronizer<T> extends LearningSynchronizer<T> {

    private final int latencyMilliseconds;

    /**
     * Create a new learning synchronizer with simulated latency.
     *
     * @param in the input stream for receiving data from the teacher
     * @param out the output stream for sending data to the teacher
     * @param newRoot the root node of the tree being reconstructed
     * @param view the learner's view into the merkle tree
     * @param latencyMilliseconds the simulated latency in milliseconds
     * @param breakConnection a callback to disconnect the connection on failure
     * @param reconnectConfig the reconnect configuration
     */
    public LaggingLearningSynchronizer(
            @NonNull final DataInputStream in,
            @NonNull final DataOutputStream out,
            @NonNull final Hashable newRoot,
            @NonNull final LearnerTreeView<T> view,
            final int latencyMilliseconds,
            @NonNull final Runnable breakConnection,
            @NonNull final ReconnectConfig reconnectConfig) {
        super(getStaticThreadManager(), in, out, newRoot, view, breakConnection, reconnectConfig);

        this.latencyMilliseconds = latencyMilliseconds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AsyncOutputStream buildOutputStream(
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final DataOutputStream out,
            @NonNull final ReconnectConfig reconnectConfig) {
        return new LaggingAsyncOutputStream(out, workGroup, latencyMilliseconds, reconnectConfig);
    }
}
