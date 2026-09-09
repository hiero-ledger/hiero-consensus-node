// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting;

import com.swirlds.base.units.UnitConstants;

/**
 * An interface for getting the number of bytes that have passed through a stream since the last reset and since creation of the stream.
 */
public interface ByteCounter {

    /**
     * The number of bytes that have passed by this stream since the last reset
     *
     * @return the number of bytes that have passed by this stream since the last reset
     */
    long getCount();

    /**
     * The number of bytes that have passed by this stream since the creation, regardless of resets
     *
     * @return total number of bytes since the creation of the stream
     */
    long getTotalCount();

    /**
     * Get the number of bytes, in kibibytes.
     *
     * @return the number of bytes that have passed by this stream since the last reset
     */
    default double getKibiBytes() {
        return getCount() * UnitConstants.BYTES_TO_KIBIBYTES;
    }

    /**
     * Get the number of bytes, in mebibytes.
     *
     * @return the number of bytes that have passed by this stream since the last reset
     */
    default double getMebiBytes() {
        return getCount() * UnitConstants.BYTES_TO_MEBIBYTES;
    }

    /**
     * Get the number of bytes, in gibibytes.
     *
     * @return the number of bytes that have passed by this stream since the last reset
     */
    default double getGibiBytes() {
        return getCount() * UnitConstants.BYTES_TO_GIBIBYTES;
    }

    /**
     * Returns the number bytes read and resets the count to 0
     *
     * @return the number of bytes read since the last reset
     */
    long getAndReset();
}
