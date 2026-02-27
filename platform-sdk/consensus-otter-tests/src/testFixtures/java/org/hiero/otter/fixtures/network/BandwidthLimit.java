// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents network bandwidth with various unit conversions. Provides type safety and clarity for bandwidth
 * specifications.
 */
@SuppressWarnings("unused")
public class BandwidthLimit implements Comparable<BandwidthLimit> {

    private static final int UNLIMITED_KILOBYTES_PER_SECOND = Integer.MAX_VALUE;

    /**
     * Represents an unlimited bandwidth limit.
     */
    public static final BandwidthLimit UNLIMITED_BANDWIDTH = new BandwidthLimit(UNLIMITED_KILOBYTES_PER_SECOND);

    private final int kilobytesPerSecond;

    private BandwidthLimit(final int kilobytesPerSecond) {
        if (kilobytesPerSecond <= 0) {
            throw new IllegalArgumentException("Bandwidth must be positive");
        }
        this.kilobytesPerSecond = kilobytesPerSecond;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int compareTo(@NonNull final BandwidthLimit other) {
        return kilobytesPerSecond - other.kilobytesPerSecond;
    }

    /**
     * Creates a bandwidth specification in kilobytes per second.
     *
     * @param kilobytesPerSecond the bandwidth in kilobytes per second
     * @return a new BandwidthLimit object
     */
    @NonNull
    public static BandwidthLimit ofKilobytesPerSecond(final int kilobytesPerSecond) {
        return new BandwidthLimit(kilobytesPerSecond);
    }

    /**
     * Creates a bandwidth specification in megabytes per second.
     *
     * @param megabytesPerSecond the bandwidth in megabytes per second
     * @return a new BandwidthLimit object
     */
    @NonNull
    public static BandwidthLimit ofMegabytesPerSecond(final int megabytesPerSecond) {
        if (megabytesPerSecond > Integer.MAX_VALUE / 1024) {
            throw new IllegalArgumentException("Bandwidth in megabytes per second is too large");
        }
        return new BandwidthLimit(megabytesPerSecond * 1024);
    }

    /**
     * Converts this bandwidth to bytes per second.
     *
     * @return the bandwidth in bytes per second
     */
    public long toBytesPerSecond() {
        return kilobytesPerSecond * 1024L;
    }

    /**
     * Converts this bandwidth to kilobytes per second.
     *
     * @return the bandwidth in kilobytes per second
     */
    public int toKilobytesPerSecond() {
        return kilobytesPerSecond;
    }

    /**
     * Converts this bandwidth to megabytes per second (rounded down).
     *
     * @return the bandwidth in megabytes per second
     */
    public int toMegabytesPerSecond() {
        return (kilobytesPerSecond + 512) / 1024;
    }

    /**
     * Checks if this bandwidth limit is unlimited.
     *
     * @return {@code true} if the bandwidth is unlimited, {@code false} otherwise
     */
    public boolean isUnlimited() {
        return kilobytesPerSecond == UNLIMITED_KILOBYTES_PER_SECOND;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final BandwidthLimit that = (BandwidthLimit) o;
        return kilobytesPerSecond == that.kilobytesPerSecond;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return kilobytesPerSecond;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return kilobytesPerSecond + " KB/s";
    }
}
