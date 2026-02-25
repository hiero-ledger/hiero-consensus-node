// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static com.hedera.node.app.fixtures.AppTestBase.METRIC_EXECUTOR;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.io.filesystem.FileSystemManager;
import com.swirlds.common.io.utility.NoOpRecycleBin;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Random;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;

/**
 * A fake implementation of the {@link Platform} interface.
 */
public final class FakePlatform implements Platform {
    private final NodeId selfNodeId;
    private final Roster roster;
    private final PlatformContext context;
    private final NotificationEngine notificationEngine;
    private final Random random = new Random(12345L);

    /**
     * Constructor for Embedded Hedera that uses a single node network
     */
    public FakePlatform() {
        this.selfNodeId = NodeId.of(0L);
        this.roster = Roster.newBuilder()
                .rosterEntries(RosterEntry.newBuilder()
                        .nodeId(selfNodeId.id())
                        .weight(500L)
                        .build())
                .build();

        this.context = createPlatformContext();
        this.notificationEngine = NotificationEngine.buildEngine(getStaticThreadManager());
    }

    /**
     * Constructor for an app test that uses multiple nodes in the network
     * @param nodeId the node id
     * @param roster the roster
     */
    public FakePlatform(final long nodeId, final Roster roster) {
        this.selfNodeId = NodeId.of(nodeId);
        this.roster = roster;
        this.context = createPlatformContext();
        this.notificationEngine = NotificationEngine.buildEngine(getStaticThreadManager());
    }

    /**
     * Create a platform context
     * @return the platform context
     */
    private PlatformContext createPlatformContext() {
        final Configuration configuration = HederaTestConfigBuilder.createConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final var metrics = new DefaultPlatformMetrics(
                selfNodeId,
                new MetricKeyRegistry(),
                METRIC_EXECUTOR,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        return PlatformContext.create(
                configuration,
                Time.getCurrent(),
                metrics,
                FileSystemManager.create(configuration),
                new NoOpRecycleBin());
    }

    @Override
    public PlatformContext getContext() {
        return context;
    }

    @Override
    public NotificationEngine getNotificationEngine() {
        return notificationEngine;
    }

    @Override
    public Signature sign(byte[] bytes) {
        return null;
    }

    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {}

    @Override
    public Roster getRoster() {
        return roster;
    }

    @Override
    public NodeId getSelfId() {
        return selfNodeId;
    }

    @Override
    @NonNull
    public <T extends State> AutoCloseableWrapper<T> getLatestImmutableState(String reason) {
        return null;
    }

    @Override
    public void start() {}

    @Override
    public void destroy() throws InterruptedException {
        notificationEngine.shutdown();
        getMetricsProvider().removePlatformMetrics(selfNodeId);
    }
}
