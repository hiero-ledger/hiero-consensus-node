// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.merkle.synchronization.LearningSynchronizer;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.views.LearnerTreeView;
import java.util.function.Supplier;
import org.hiero.base.crypto.Hashable;
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
     */
    public LaggingLearningSynchronizer(
            final SerializableDataInputStream in,
            final SerializableDataOutputStream out,
            final Hashable newRoot,
            final LearnerTreeView view,
            final int latencyMilliseconds,
            final Runnable breakConnection,
            final ReconnectConfig reconnectConfig) {
        super(getStaticThreadManager(), in, out, newRoot, view, breakConnection, reconnectConfig);

        this.latencyMilliseconds = latencyMilliseconds;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected AsyncOutputStream buildOutputStream(
            final StandardWorkGroup workGroup,
            final SerializableDataOutputStream out,
            final Supplier<Boolean> alive,
            final ReconnectConfig reconnectConfig) {
        return new LaggingAsyncOutputStream(out, workGroup, alive, latencyMilliseconds, reconnectConfig);
    }
}
