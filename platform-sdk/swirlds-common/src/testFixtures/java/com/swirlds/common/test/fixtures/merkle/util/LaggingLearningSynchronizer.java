// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A {@link LearningSynchronizer} with simulated latency.
 */
public class LaggingLearningSynchronizer extends LearningSynchronizer {

    private final int latencyMilliseconds;

    /**
     * Create a new learning synchronizer with simulated latency.
     * @param metrics a Metrics object
     */
    public LaggingLearningSynchronizer(
            final SerializableDataInputStream in,
            final SerializableDataOutputStream out,
            final MerkleNode newRoot,
            final LearnerTreeView<?> view,
            final int latencyMilliseconds,
            final Runnable breakConnection,
            final ReconnectConfig reconnectConfig,
            @NonNull final Metrics metrics) {
        super(getStaticThreadManager(), in, out, newRoot, view, breakConnection, reconnectConfig);

        this.latencyMilliseconds = latencyMilliseconds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends SelfSerializable> AsyncOutputStream<T> buildOutputStream(
            final StandardWorkGroup workGroup, final SerializableDataOutputStream out) {
        return new LaggingAsyncOutputStream<>(out, workGroup, latencyMilliseconds, reconnectConfig);
    }
}
