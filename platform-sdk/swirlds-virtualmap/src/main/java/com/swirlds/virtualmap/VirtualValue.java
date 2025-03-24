// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import com.swirlds.common.FastCopyable;
import java.nio.ByteBuffer;
import org.hiero.consensus.model.io.SelfSerializable;

/**
 * A {@link VirtualValue} is a "virtual" value, and is part of the API for the {@code VirtualMap}.
 * {@code VirtualMap}s, by their nature, need both keys and values which are serializable
 * and {@link FastCopyable}. To enhance performance, serialization methods that work with
 * {@link ByteBuffer} are required on a VValue.
 */
public interface VirtualValue extends SelfSerializable, FastCopyable {

    @Override
    VirtualValue copy();

    /**
     * Gets a copy of this Value which is entirely read-only.
     *
     * @return A non-null copy that is read-only. Can be a view rather than a copy.
     */
    VirtualValue asReadOnly();
}
