// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.sync;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.common.test.fixtures.threading.SyncPhaseParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutor;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.gossip.shadowgraph.ShadowgraphSynchronizer;
import com.swirlds.platform.gossip.sync.config.SyncConfig_;
import com.swirlds.platform.network.Connection;

/**
 * This class initiates a sync between a caller and listener node.
 */
public class Synchronizer {

    // The parallel executor used to execute the caller's and listener's synchronize at the same time
    private final ParallelExecutor parallelExecutor;

    public Synchronizer() {
        parallelExecutor = new SyncPhaseParallelExecutor(getStaticThreadManager(), null, null, false);
    }

    /**
     * Performs synchronization between the caller and listener nodes.
     *
     * The {@link ShadowgraphSynchronizer#synchronize(PlatformContext, Connection)} method is
     * invoked on each node in parallel using the {@link ParallelExecutor}.
     *
     * @throws Exception
     * 		is there is any exception during connection setup ornode  synchronization
     */
    public void synchronize(final SyncNode caller, final SyncNode listener) throws Exception {

        // The original sync tests are incompatible with event filtering and reduced sync event counts.
        final Configuration configuration = new TestConfigBuilder()
                .withValue(SyncConfig_.FILTER_LIKELY_DUPLICATES, false)
                .withValue(SyncConfig_.MAX_SYNC_EVENT_COUNT, 0)
                .getOrCreateConfig();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        parallelExecutor.doParallel(
                () -> {
                    try {
                        final boolean synchronize =
                                caller.getSynchronizer().synchronize(platformContext, caller.getConnection());
                        caller.setSynchronizerReturn(synchronize);
                    } catch (final Exception e) {
                        caller.setSynchronizerReturn(null);
                        caller.setSyncException(e);
                        caller.getConnection().disconnect();
                        throw e;
                    }
                    // ignored
                    return null;
                },
                () -> {
                    try {
                        if (listener.isCanAcceptSync()) {
                            final boolean synchronize =
                                    listener.getSynchronizer().synchronize(platformContext, listener.getConnection());
                            listener.setSynchronizerReturn(synchronize);
                        }
                    } catch (final Exception e) {
                        listener.setSynchronizerReturn(null);
                        listener.setSyncException(e);
                        listener.getConnection().disconnect();
                        throw e;
                    }
                });
    }
}
