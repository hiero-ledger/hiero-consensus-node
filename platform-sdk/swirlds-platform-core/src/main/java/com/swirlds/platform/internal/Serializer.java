// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.internal;

import com.swirlds.common.io.streams.SerializableDataOutputStream;
import java.io.IOException;

public interface Serializer<T> {
    void serialize(T object, SerializableDataOutputStream stream) throws IOException;
}
