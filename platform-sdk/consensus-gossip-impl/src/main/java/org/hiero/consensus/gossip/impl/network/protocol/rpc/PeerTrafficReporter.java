// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import static com.swirlds.logging.legacy.LogMarker.SOCKET_EXCEPTIONS;

import com.swirlds.base.time.Time;
import com.swirlds.logging.legacy.payload.PeerTrafficShapingPayload;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.throttle.RateLimiter;
import org.hiero.consensus.gossip.config.TrafficShapingConfig;
import org.hiero.consensus.model.node.NodeId;

/**
 * Reports how much of its inbound byte budget a peer is using. Two thresholds: above the low watermark a warning is
 * logged, above the high watermark an evidence record is produced. Neither disconnects the peer, and neither affects
 * how hard the peer is shaped, which follows continuously from the budget itself.
 *
 * <p>Repeat suppression is delegated to {@link RateLimiter}. Occupancy is sampled
 * per message, so a peer sitting near a threshold would otherwise generate a log line per message.
 *
 * <p>This class is not thread safe. One instance is owned by the rpc read thread for a single peer.
 */
public class PeerTrafficReporter {

    private static final Logger logger = LogManager.getLogger(PeerTrafficReporter.class);

    private final TrafficShapingConfig config;
    private final NodeId peerId;
    private final Time time;
    private final RateLimiter warningLimiter;

    public PeerTrafficReporter(
            @NonNull final Time time, @NonNull final TrafficShapingConfig config, @NonNull final NodeId peerId) {
        this.time = Objects.requireNonNull(time);
        this.config = Objects.requireNonNull(config);
        this.peerId = Objects.requireNonNull(peerId);
        this.warningLimiter = new RateLimiter(time, config.reportInterval());
    }

    /**
     * Report the current budget occupancy for this peer.
     *
     * @param occupancy  fraction of the burst budget consumed, from 0.0 to 1.0
     * @param delayNanos the pause that was applied, or 0 if the peer is within budget
     */
    public void report(final double occupancy, final long delayNanos) {

        if (occupancy >= config.highWatermark()) {
            // here, possible integration with Sheriff module should be added to report breaches
            // disconnect and possibly shun offending node
            // for now, reporting it as an error, as it indicates either broken code or misconfigured limits
            // which would lead to broken production environment
            logger.warn(
                    SOCKET_EXCEPTIONS.getMarker(),
                    new PeerTrafficShapingPayload(
                            "Peer inbound traffic budget high watermark breached, misconfigured settings",
                            peerId.id(),
                            Math.round(occupancy * 1000),
                            Math.round(config.highWatermark() * 1000),
                            delayNanos,
                            config.enforce()));
        }

        if (occupancy >= config.lowWatermark() && warningLimiter.requestAndTrigger()) {
            logger.warn(
                    SOCKET_EXCEPTIONS.getMarker(),
                    new PeerTrafficShapingPayload(
                            "Peer inbound traffic budget watermark breached",
                            peerId.id(),
                            Math.round(occupancy * 1000),
                            Math.round(config.lowWatermark() * 1000),
                            delayNanos,
                            config.enforce()));
        }
    }
}
