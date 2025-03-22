// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.io;

import org.hiero.consensus.model.constructable.RuntimeConstructable;

/**
 * An object implementing this interface will have a way of serializing and deserializing itself. This
 * serialization must deterministically generate the same output bytes every time. If the bytes generated
 * by the serialization algorithm change due to code changes then then this must be captured via a protocol
 * version increase. SerializableDet objects are required to maintain the capability of deserializing objects
 * serialized using old protocols.
 */
public interface SerializableDet extends RuntimeConstructable, Versioned {

    /**
     * Any version lower than this is not supported and will cause
     * an exception to be thrown if it is attempted to be used.
     *
     * @return minimum supported version number
     */
    default int getMinimumSupportedVersion() {
        return 1;
    }
}
