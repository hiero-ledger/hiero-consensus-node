// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.hedera.embedded.fakes;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.crypto.Signature;
import com.swirlds.common.crypto.SignatureType;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.common.utility.AutoCloseableWrapper;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.platform.listeners.PlatformStatusChangeNotification;
import com.swirlds.platform.system.Platform;
import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.consensus.model.node.NodeId;

public abstract class AbstractFakePlatform implements Platform {
    private static final Signature TOY_SIGNATURE = new Signature(SignatureType.RSA, new byte[384]);
    protected static final long NANOS_BETWEEN_CONS_EVENTS = 1_000;

    protected final AtomicLong roundNo = new AtomicLong(1);
    protected final AtomicLong consensusOrder = new AtomicLong(1);

    private final NodeId selfId;
    private final Roster roster;
    private final PlatformContext platformContext;
    private final FakeNotificationEngine notificationEngine = new FakeNotificationEngine();

    public AbstractFakePlatform(
            @NonNull final NodeId selfId,
            @NonNull final Roster roster,
            @NonNull final ScheduledExecutorService executorService,
            @NonNull final Metrics metrics) {
        requireNonNull(metrics);
        requireNonNull(executorService);
        this.selfId = requireNonNull(selfId);
        this.roster = requireNonNull(roster);
        this.platformContext = new FakePlatformContext(selfId, executorService, metrics);
    }

    /**
     * Notifies listeners of a platform status change.
     *
     * @param notification the notification to send
     */
    public void notifyListeners(@NonNull final PlatformStatusChangeNotification notification) {
        notificationEngine.statusChangeListeners.forEach(l -> l.notify(notification));
    }

    /**
     * Returns the number of the last consensus round.
     */
    public long lastRoundNo() {
        return roundNo.get() - 1;
    }

    @NonNull
    @Override
    public Signature sign(@NonNull final byte[] data) {
        return TOY_SIGNATURE;
    }

    @NonNull
    @Override
    public PlatformContext getContext() {
        return platformContext;
    }

    @NonNull
    @Override
    public NodeId getSelfId() {
        return selfId;
    }

    @Override
    public @NonNull <T extends State> AutoCloseableWrapper<T> getLatestImmutableState(@NonNull String reason) {
        throw new UnsupportedOperationException("Not used by Hedera");
    }

    @NonNull
    @Override
    public Roster getRoster() {
        return roster;
    }

    @NonNull
    @Override
    public NotificationEngine getNotificationEngine() {
        return notificationEngine;
    }
}
