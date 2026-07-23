package com.swirlds.platform.adapter;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.notification.NotificationEngine;
import com.swirlds.platform.system.Platform;
import org.hiero.base.crypto.Signature;
import org.hiero.consensus.ConsensusLayer;
import org.hiero.consensus.ExecutionLayerCallbacks;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.jspecify.annotations.NonNull;

public class ExecutionImposter implements Platform {

    @NonNull
    private final ConsensusLayer consensusLayer;

    public ExecutionImposter(@NonNull final ConsensusLayer consensusLayer) {
        this.consensusLayer = requireNonNull(consensusLayer);
    }

    @Override
    public @NonNull PlatformContext getContext() {
        return null;
    }

    @Override
    public @NonNull NotificationEngine getNotificationEngine() {
        return null;
    }

    @Override
    public @NonNull Roster getRoster() {
        return null;
    }

    @Override
    public @NonNull NodeId getSelfId() {
        return null;
    }

    @Override
    public @NonNull Signature sign(@NonNull byte[] data) {
        return null;
    }

    @Override
    public void quiescenceCommand(@NonNull final QuiescenceCommand quiescenceCommand) {
        consensusLayer.quiescenceCommand(quiescenceCommand);
    }

    @Override
    public void start() {

    }

    @Override
    public void destroy() throws InterruptedException {

    }
}
