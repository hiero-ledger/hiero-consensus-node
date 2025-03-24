// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.exceptions;

import java.io.IOException;
import org.hiero.consensus.model.io.SerializableDet;

/**
 * Exception that is caused when illegal version is read from the stream
 */
public class InvalidVersionException extends IOException {
    public InvalidVersionException(final int expectedVersion, final int version) {
        super(String.format("Illegal version %d was read from the stream. Expected %d", version, expectedVersion));
    }

    public InvalidVersionException(final int minimumVersion, final int maximumVersion, final int version) {
        super(String.format(
                "Illegal version %d was read from the stream. Expected version in the range %d - %d",
                version, minimumVersion, maximumVersion));
    }

    public InvalidVersionException(final int version, SerializableDet object) {
        super(String.format(
                "Illegal version %d was read from the stream for %s (class ID %d(0x%08X)). Expected version in the "
                        + "range %d - %d",
                version,
                object.getClass(),
                object.getClassId(),
                object.getClassId(),
                object.getMinimumSupportedVersion(),
                object.getVersion()));
    }
}
