// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.streams.MerkleDataInputStream;
import com.swirlds.common.io.streams.MerkleDataOutputStream;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * A {@link TeachingSynchronizer} with simulated latency.
 */
public class LaggingTeachingSynchronizer extends TeachingSynchronizer {

    private final int latencyMilliseconds;

    /**
     * Create a new teaching synchronizer with simulated latency.
     */
    public LaggingTeachingSynchronizer(
            @NonNull final PlatformContext platformContext,
            final MerkleDataInputStream in,
            final MerkleDataOutputStream out,
            final MerkleNode root,
            final int latencyMilliseconds,
            final Runnable breakConnection,
            final ReconnectConfig reconnectConfig) {
        super(
                platformContext.getConfiguration(),
                Time.getCurrent(),
                getStaticThreadManager(),
                in,
                out,
                root,
                breakConnection,
                reconnectConfig);
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
