// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.test.fixtures.merkle.util;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.base.time.Time;
import com.swirlds.common.merkle.synchronization.TeachingSynchronizer;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.synchronization.views.TeacherTreeView;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * A {@link TeachingSynchronizer} with simulated latency.
 */
public class LaggingTeachingSynchronizer extends TeachingSynchronizer {

    private final int latencyMilliseconds;

    /**
     * Create a new teaching synchronizer with simulated latency.
     */
    public LaggingTeachingSynchronizer(
            final SerializableDataInputStream in,
            final SerializableDataOutputStream out,
            final TeacherTreeView<?> view,
            final int latencyMilliseconds,
            final Runnable breakConnection,
            final ReconnectConfig reconnectConfig) {
        super(Time.getCurrent(), getStaticThreadManager(), in, out, view, breakConnection, reconnectConfig);
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
