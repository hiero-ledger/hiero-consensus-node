package org.hiero.consensus.gossip.impl.reconnect;

import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.PeerProtocol;
import org.hiero.consensus.main.model.PeerProtocolFactory;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

public class ReconnectProxyProtocolFactory implements PeerProtocolFactory {

    @NonNull
    private final Metrics metrics;
    @NonNull
    private final Time time;
    @Nullable
    private PeerProtocolFactory executionProtocolFactory;
    @NonNull
    private final FallenBehindMonitor fallenBehindMonitor;

    public ReconnectProxyProtocolFactory(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final FallenBehindMonitor fallenBehindMonitor) {

        this.metrics = requireNonNull(metrics);
        this.time = requireNonNull(time);
        this.fallenBehindMonitor = requireNonNull(fallenBehindMonitor);
    }

    public void setExecutionProtocolFactory(@NonNull final PeerProtocolFactory executionProtocolFactory) {
        this.executionProtocolFactory = executionProtocolFactory;
    }

    @Override
    public PeerProtocol createPeerInstance(@NonNull final NodeId peerId) {
        requireNonNull(executionProtocolFactory, "Not initialized");
        return new ReconnectProxyProtocol(
                metrics,
                time,
                peerId,
                executionProtocolFactory.createPeerInstance(peerId),
                fallenBehindMonitor
        );
    }
}
