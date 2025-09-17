// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Objects;
import org.hiero.consensus.crypto.PlatformSigner;
import org.hiero.consensus.event.creator.EventCreationManager;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;

public class EventCreationManagerService implements EventCreationManager {

    private DefaultEventCreationManager eventCreationManager;

    @Override
    public void initialize(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SecureRandom random,
            @NonNull final KeysAndCerts keysAndCerts,
            @NonNull final Roster roster,
            @NonNull final NodeId selfId,
            @NonNull final EventTransactionSupplier transactionSupplier,
            @NonNull final SignatureTransactionCheck signatureTransactionCheck) {
        final EventCreator eventCreator = new TipsetEventCreator(
                configuration,
                metrics,
                time,
                random,
                data -> new PlatformSigner(keysAndCerts).sign(data),
                roster,
                selfId,
                transactionSupplier);

        eventCreationManager =
                new DefaultEventCreationManager(configuration, metrics, time, signatureTransactionCheck, eventCreator);
    }

    @Nullable
    @Override
    public PlatformEvent maybeCreateEvent() {
        return Objects.requireNonNull(eventCreationManager).maybeCreateEvent();
    }

    @Override
    public void registerEvent(@NonNull final PlatformEvent event) {
        Objects.requireNonNull(eventCreationManager).registerEvent(event);
    }

    @Override
    public void setEventWindow(@NonNull final EventWindow eventWindow) {
        Objects.requireNonNull(eventCreationManager).setEventWindow(eventWindow);
    }

    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        Objects.requireNonNull(eventCreationManager).updatePlatformStatus(platformStatus);
    }

    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        Objects.requireNonNull(eventCreationManager).reportUnhealthyDuration(duration);
    }

    @Override
    public void reportSyncRoundLag(@NonNull final Double lag) {
        Objects.requireNonNull(eventCreationManager).reportSyncRoundLag(lag);
    }

    @Override
    public void clear() {
        Objects.requireNonNull(eventCreationManager).clear();
    }
}
