// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.stream;

/**
 * Describes an object that requires specific alignment within a stream.
 * Linguistically similar to, but in no way actually related to "streamlined".
 */
public interface StreamAligned {

    long NO_ALIGNMENT = Long.MIN_VALUE;

    /**
     * Gets the stream alignment descriptor for the object, or {@link #NO_ALIGNMENT} if
     * this object does not care about its stream alignment. If two or more sequential
     * objects have the same stream alignment (excluding {@link #NO_ALIGNMENT})
     * then those objects are grouped together.
     *
     * @return the stream alignment of the object
     */
    default long getStreamAlignment() {
        return NO_ALIGNMENT;
    }
}
