// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.network;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Represents network bandwidth with various unit conversions. Provides type safety and clarity for bandwidth
 * specifications.
 */
@SuppressWarnings("unused")
public class BandwidthLimit {

    private static final long UNLIMITED_BYTES_PER_SECOND = Long.MAX_VALUE;

    /**
     * Represents an unlimited bandwidth limit.
     */
    public static final BandwidthLimit UNLIMITED = new BandwidthLimit(UNLIMITED_BYTES_PER_SECOND);

    private final long bytesPerSecond;

    private BandwidthLimit(final long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            throw new IllegalArgumentException("Bandwidth cannot be negative");
        }
        this.bytesPerSecond = bytesPerSecond;
    }

    /**
     * Creates a bandwidth specification in bytes per second.
     *
     * @param bytesPerSecond the bandwidth in bytes per second
     * @return a new BandwidthLimit object
     */
    @NonNull
    public static BandwidthLimit ofBytesPerSecond(final long bytesPerSecond) {
        return new BandwidthLimit(bytesPerSecond);
    }

    /**
     * Creates a bandwidth specification in kilobytes per second.
     *
     * @param kilobytesPerSecond the bandwidth in kilobytes per second
     * @return a new BandwidthLimit object
     */
    @NonNull
    public static BandwidthLimit ofKilobytesPerSecond(final long kilobytesPerSecond) {
        return new BandwidthLimit(kilobytesPerSecond * 1024L);
    }

    /**
     * Creates a bandwidth specification in megabytes per second.
     *
     * @param megabytesPerSecond the bandwidth in megabytes per second
     * @return a new BandwidthLimit object
     */
    @NonNull
    public static BandwidthLimit ofMegabytesPerSecond(final long megabytesPerSecond) {
        return new BandwidthLimit(megabytesPerSecond * 1024L * 1024L);
    }

    /**
     * Converts this bandwidth to bytes per second.
     *
     * @return the bandwidth in bytes per second
     */
    public long toBytesPerSecond() {
        return bytesPerSecond;
    }

    /**
     * Checks if this bandwidth limit is unlimited.
     *
     * @return {@code true} if the bandwidth is unlimited, {@code false} otherwise
     */
    public boolean isUnlimited() {
        return bytesPerSecond == UNLIMITED_BYTES_PER_SECOND;
    }
}
