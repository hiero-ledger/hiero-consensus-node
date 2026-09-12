// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.network.protocol.rpc;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import org.hiero.consensus.gossip.config.TrafficShapingConfig;

/**
 * Per-peer inbound byte budget. Bytes read from a peer are charged against a token bucket; when the peer is over
 * budget, {@link #charge(long)} returns how long the reader should pause.
 *
 * <p>Implemented as a GCRA, which is equivalent to a token bucket but holds a single long of state and needs no
 * refill timer. {@code tatNanos} is a virtual clock running ahead of real time in proportion to the bytes charged;
 * how far ahead it is allowed to run is the burst allowance.
 *
 * <p>This class is not thread safe. One instance is owned by the rpc read thread for a single peer.
 */
public class PeerByteShaper {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Time time;
    private final long bytesPerSecond;
    private final long burstNanos;
    private final long ceilingNanos;

    /** Virtual clock; ahead of now by the amount of budget currently consumed. */
    private long tatNanos;

    /** Budget consumed as observed by the most recent {@link #charge(long)}, from 0.0 to 1.0. */
    private double lastOccupancy;

    /**
     * @param time   platform time source; must be monotonic, and injectable for tests
     * @param config traffic shaping configuration
     */
    public PeerByteShaper(@NonNull final Time time, @NonNull final TrafficShapingConfig config) {
        this.time = Objects.requireNonNull(time);
        this.bytesPerSecond = config.peerBytesPerSecond();
        // multiplyExact so a misconfigured burst fails at startup rather than silently wrapping
        this.burstNanos = Math.multiplyExact(config.peerBurstBytes(), NANOS_PER_SECOND) / bytesPerSecond;
        this.ceilingNanos = burstNanos + config.maxReadDelay().toNanos();
        this.tatNanos = time.nanoTime();
    }

    /**
     * Charge the bytes read from this peer since the previous call.
     *
     * @param bytes bytes read since the last call; bounded in practice by one message plus one socket buffer, so the
     *              multiplication below cannot overflow for any sane {@code maxMessageBytes}
     * @return nanoseconds the reader should pause before reading again, or 0 if the peer is within budget
     */
    public long charge(final long bytes) {
        final long now = time.nanoTime();

        // an idle peer accrues the full burst allowance, but no more
        if (tatNanos < now) {
            tatNanos = now;
        }

        tatNanos += bytes * NANOS_PER_SECOND / bytesPerSecond;

        // cap the debt so one large message cannot produce an unbounded stall
        final long ceiling = now + ceilingNanos;
        if (tatNanos > ceiling) {
            tatNanos = ceiling;
        }

        final long used = tatNanos - now;
        lastOccupancy = used <= 0 ? 0.0 : Math.min(1.0, (double) used / burstNanos);

        return Math.max(0L, used - burstNanos);
    }

    /**
     * Budget consumed as of the last {@link #charge(long)} call: 0.0 idle, 1.0 burst exhausted. Reported rather than
     * recomputed so that only one clock read happens per message.
     *
     * @return the last observed occupancy
     */
    public double lastOccupancy() {
        return lastOccupancy;
    }
}
