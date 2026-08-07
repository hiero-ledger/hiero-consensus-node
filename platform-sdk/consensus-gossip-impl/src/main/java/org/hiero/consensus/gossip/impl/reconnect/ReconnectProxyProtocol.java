package org.hiero.consensus.gossip.impl.reconnect;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;
import static com.swirlds.metrics.api.FloatFormats.FORMAT_10_0;
import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.throttle.RateLimitedLogger;
import org.hiero.consensus.main.model.Connection;
import org.hiero.consensus.main.model.NetworkProtocolException;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.main.model.PeerProtocol;
import org.hiero.consensus.metrics.extensions.CountPerSecond;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

public class ReconnectProxyProtocol implements PeerProtocol {

    private static final Logger logger = LogManager.getLogger();

    @NonNull
    private final PeerProtocol executionProtocol;

    private final NodeId peerId;

    private final FallenBehindMonitor fallenBehindMonitor;

    private final CountPerSecond reconnectRejectionMetrics;

    /**
     * A rate limited logger for when rejecting teacher role due to falling behind.
     */
    private final RateLimitedLogger fallenBehindLogger;

    public ReconnectProxyProtocol(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final NodeId peerId,
            @NonNull final PeerProtocol executionProtocol,
            @NonNull final FallenBehindMonitor fallenBehindMonitor) {

        this.executionProtocol = requireNonNull(executionProtocol);
        this.peerId = requireNonNull(peerId);
        this.fallenBehindMonitor = requireNonNull(fallenBehindMonitor);
        fallenBehindLogger = new RateLimitedLogger(logger, time, Duration.ofMinutes(1));

        this.reconnectRejectionMetrics = new CountPerSecond(
                metrics,
                new CountPerSecond.Config(
                        PLATFORM_CATEGORY, String.format("reconnectRejections_per_sec_%02d", peerId.id()))
                        .withDescription(String.format(
                                "number of reconnections rejected per second from node %02d", peerId.id()))
                        .withUnit("rejectionsPerSec")
                        .withFormat(FORMAT_10_0));
    }

    @Override
    public boolean shouldInitiate() {
        // if this neighbor has not told me I have fallen behind, I will not reconnect with him
        if (!fallenBehindMonitor.hasFallenBehind()) {
            return false;
        }
        if (!fallenBehindMonitor.isBehindPeer(peerId)) {
            return false;
        }
        return executionProtocol.shouldInitiate();
    }

    @Override
    public void initiateFailed() {
        executionProtocol.initiateFailed();
    }

    @Override
    public boolean shouldAccept() {
        // we should not be the teacher if we have fallen behind
        if (fallenBehindMonitor.hasFallenBehind()) {
            fallenBehindLogger.info(
                    RECONNECT.getMarker(),
                    "Rejecting reconnect request from node {} because this node has fallen behind",
                    peerId);
            reconnectRejected();
            return false;
        }

        if (executionProtocol.shouldAccept()) {
            return true;
        }

        reconnectRejected();
        return false;
    }

    @Override
    public void acceptFailed() {
        executionProtocol.acceptFailed();
    }

    @Override
    public boolean acceptOnSimultaneousInitiate() {
        return executionProtocol.acceptOnSimultaneousInitiate();
    }

    @Override
    public void runProtocol(@NonNull final Connection connection) throws NetworkProtocolException, IOException, InterruptedException {
        executionProtocol.runProtocol(connection);
    }

    /**
     * Called when we reject a reconnect as a teacher
     */
    private void reconnectRejected() {
        reconnectRejectionMetrics.count();
    }

}
