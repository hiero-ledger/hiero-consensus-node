/*
 * Copyright (C) 2021-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.merkledb.test.fixtures;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.nio.ByteBuffer;

public class ExampleLongKey {

    public static Bytes longToKey(final long k) {
        final byte[] bytes = new byte[8];
        ByteBuffer.wrap(bytes).putLong(k);
        return Bytes.wrap(bytes);
    }

    public static long keyToLong(final Bytes key) {
        return key.getLong(0);
    }

    private ExampleLongKey() {}
}
