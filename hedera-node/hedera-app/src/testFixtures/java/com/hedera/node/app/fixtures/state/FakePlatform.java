// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fixtures.state;

import static com.hedera.node.app.fixtures.AppTestBase.METRIC_EXECUTOR;
import static com.swirlds.platform.builder.internal.StaticPlatformBuilder.getMetricsProvider;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.base.crypto.Signature;
import org.hiero.base.file.FileSystemManager;
import org.hiero.consensus.config.PathsConfig;
import org.hiero.consensus.io.NoOpRecycleBin;
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
        this.notificationEngine = NotificationEngine.buildEngine();
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
        this.notificationEngine = NotificationEngine.buildEngine();
    }

    /**
     * Create a platform context
     * @return the platform context
     */
    private PlatformContext createPlatformContext() {
        final Configuration configuration = HederaTestConfigBuilder.createConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final PathsConfig pathsConfig = configuration.getConfigData(PathsConfig.class);
        final FileSystemManager fileSystemManager =
                new FileSystemManager(pathsConfig.savedStateDir(), pathsConfig.tmpDir());
        final Metrics metrics = new DefaultPlatformMetrics(
                selfNodeId,
                new MetricKeyRegistry(),
                METRIC_EXECUTOR,
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
        return PlatformContext.create(
                configuration, Time.getCurrent(), metrics, fileSystemManager, new NoOpRecycleBin());
    }

    @Override
    @NonNull
    public PlatformContext getContext() {
        return context;
    }

    @Override
    @NonNull
    public NotificationEngine getNotificationEngine() {
        return notificationEngine;
    }

    @Override
    @NonNull
    public Signature sign(@NonNull byte[] bytes) {
        return null;
    }

    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {}

    @Override
    @NonNull
    public Roster getRoster() {
        return roster;
    }

    @Override
    @NonNull
    public NodeId getSelfId() {
        return selfNodeId;
    }

    @Override
    public void start() {}

    @Override
    public void destroy() throws InterruptedException {
        notificationEngine.shutdown();
        getMetricsProvider().removePlatformMetrics(selfNodeId);
    }
}
