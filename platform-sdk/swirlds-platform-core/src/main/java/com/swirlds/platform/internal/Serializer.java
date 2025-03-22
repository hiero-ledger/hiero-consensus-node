// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

public interface Serializer<T> {
    void serialize(T object, SerializableDataOutputStream stream) throws IOException;
}
