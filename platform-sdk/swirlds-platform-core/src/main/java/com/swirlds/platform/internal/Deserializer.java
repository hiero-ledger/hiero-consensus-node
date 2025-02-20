// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

import com.swirlds.common.io.streams.SerializableDataInputStream;
import java.io.IOException;

public interface Deserializer<T> {
    T deserialize(SerializableDataInputStream stream) throws IOException;
}
