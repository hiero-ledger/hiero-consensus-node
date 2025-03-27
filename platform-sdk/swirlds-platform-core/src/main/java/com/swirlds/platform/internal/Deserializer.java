// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

import java.io.IOException;
import org.hiero.consensus.model.io.streams.SerializableDataInputStream;

public interface Deserializer<T> {
    T deserialize(SerializableDataInputStream stream) throws IOException;
}
