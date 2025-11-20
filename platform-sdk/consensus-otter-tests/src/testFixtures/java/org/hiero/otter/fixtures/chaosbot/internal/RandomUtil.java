// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.otter.fixtures.network.BandwidthLimit;

/**
 * Utility methods for generating random values.
 */
public class RandomUtil {

    private RandomUtil() {}

    /**
     * Generate a random duration based on a Gaussian distribution.
     *
     * @param randotron the random number generator
     * @param mean the mean duration
     * @param stdDev the standard deviation of the duration
     * @return a random duration
     */
    public static Duration randomGaussianDuration(
            @NonNull final Randotron randotron, @NonNull final Duration mean, @NonNull final Duration stdDev) {
        final long jitterSeconds =
                Math.max(-mean.getSeconds() + 1, (long) (randotron.nextGaussian() * stdDev.getSeconds()));
        return mean.plusSeconds(jitterSeconds);
    }

    /**
     * Generate a random bandwidth limit based on a Gaussian distribution.
     *
     * @param randotron the random number generator
     * @param mean the mean bandwidth limit
     * @param stdDev the standard deviation of the bandwidth limit
     * @return a random bandwidth limit
     */
    public static BandwidthLimit randomGaussianBandwidthLimit(
            @NonNull final Randotron randotron,
            @NonNull final BandwidthLimit mean,
            @NonNull final BandwidthLimit stdDev) {
        final int jitterKbps = (int) (randotron.nextGaussian() * stdDev.toKilobytesPerSecond());
        return BandwidthLimit.ofKilobytesPerSecond(Math.max(1, mean.toKilobytesPerSecond() + jitterKbps));
    }
}
