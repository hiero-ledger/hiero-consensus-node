// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.withinPercentage;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.ConfigurationBuilder;
import java.time.Duration;
import org.hiero.consensus.gossip.config.TrafficShapingConfig;
import org.hiero.consensus.gossip.config.TrafficShapingConfig_;
import org.hiero.consensus.gossip.impl.network.protocol.rpc.PeerByteShaper;
import org.junit.jupiter.api.Test;

class PeerByteShaperTest {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long RATE = 1_000_000L; // 1 MB/s
    private static final long BURST = 4_000_000L; // 4 s of burst
    private static final Duration MAX_DELAY = Duration.ofMillis(200);

    /** One chunk costs 100ms of budget at {@link #RATE}, comfortably under {@link #MAX_DELAY}. */
    private static final long CHUNK = 100_000L;

    @Test
    void withinBurstNothingIsDelayed() {
        final FakeTime time = new FakeTime();
        final PeerByteShaper shaper = new PeerByteShaper(time, config());

        assertThat(shaper.charge(BURST)).isZero();
        assertThat(shaper.lastOccupancy()).isEqualTo(1.0);
    }

    @Test
    void exceedingBurstDelaysProportionally() {
        final FakeTime time = new FakeTime();
        final PeerByteShaper shaper = new PeerByteShaper(time, config());

        shaper.charge(BURST);
        // CHUNK at 1 MB/s is 100ms of debt beyond the exhausted burst
        assertThat(shaper.charge(CHUNK)).isEqualTo(Duration.ofMillis(100).toNanos());
    }

    @Test
    void delayNeverExceedsMaxReadDelay() {
        final FakeTime time = new FakeTime();
        final PeerByteShaper shaper = new PeerByteShaper(time, config());

        // 100 MB at 1 MB/s would be 100 seconds of debt
        assertThat(shaper.charge(100_000_000L)).isEqualTo(MAX_DELAY.toNanos());
    }

    @Test
    void idlePeerAccruesBurstButNoMore() {
        final FakeTime time = new FakeTime();
        final PeerByteShaper shaper = new PeerByteShaper(time, config());

        shaper.charge(BURST);
        time.tick(Duration.ofHours(1));

        assertThat(shaper.charge(BURST)).isZero();
        assertThat(shaper.charge(1)).isPositive();
    }

    @Test
    void occupancyIsZeroWhenIdle() {
        final FakeTime time = new FakeTime();
        final PeerByteShaper shaper = new PeerByteShaper(time, config());

        assertThat(shaper.charge(0)).isZero();
        assertThat(shaper.lastOccupancy()).isZero();
    }

    @Test
    void sustainedThroughputConvergesToConfiguredRate() {
        final FakeTime time = new FakeTime();
        final PeerByteShaper shaper = new PeerByteShaper(time, config());

        final long startNanos = time.nanoTime();
        final long runForNanos = Duration.ofSeconds(120).toNanos();
        final long readerLoopFloorNanos = Duration.ofMillis(10).toNanos();

        long delivered = 0;
        while (time.nanoTime() - startNanos < runForNanos) {
            final long delayNanos = shaper.charge(CHUNK);
            delivered += CHUNK;
            // a reader that honours the pause, but never spins faster than its own loop floor. Offering a chunk
            // every 10ms is 10 MB/s, ten times the configured rate, so the shaper has to do the work.
            time.tick(Duration.ofNanos(Math.max(delayNanos, readerLoopFloorNanos)));
        }

        final long elapsedNanos = time.nanoTime() - startNanos;
        final double elapsedSeconds = elapsedNanos / (double) NANOS_PER_SECOND;

        // The shaper never rejects bytes, so the property under test is not "fewer bytes arrived" but "virtual time
        // cannot run further ahead of real time than burst + maxReadDelay". That caps the total at
        // rate * (elapsed + burst + maxReadDelay).
        final long ceiling = Math.round(RATE * elapsedSeconds)
                + BURST
                + Math.round(RATE * (MAX_DELAY.toNanos() / (double) NANOS_PER_SECOND));
        assertThat(delivered).isLessThanOrEqualTo(ceiling);

        // It must also not over-throttle: a reader willing to go faster should achieve at least the configured rate.
        assertThat(delivered).isGreaterThanOrEqualTo(Math.round(RATE * elapsedSeconds));

        // Over a window much longer than the burst, average bandwidth is close to the configured rate. The residual
        // overshoot is the initial burst amortised over the window, so it shrinks as the window grows.
        assertThat(delivered / elapsedSeconds).isCloseTo(RATE, withinPercentage(10));
    }

    private static TrafficShapingConfig config() {
        return ConfigurationBuilder.create()
                .autoDiscoverExtensions()
                .withValue(TrafficShapingConfig_.PEER_BYTES_PER_SECOND, String.valueOf(RATE))
                .withValue(TrafficShapingConfig_.PEER_BURST_BYTES, String.valueOf(BURST))
                .withValue(TrafficShapingConfig_.MAX_READ_DELAY, String.valueOf(MAX_DELAY))
                .build()
                .getConfigData(TrafficShapingConfig.class);
    }
}
