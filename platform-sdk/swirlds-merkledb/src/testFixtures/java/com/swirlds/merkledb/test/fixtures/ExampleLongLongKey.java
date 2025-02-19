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

@SuppressWarnings("unused")
public class ExampleLongLongKey {

    public static Bytes longToKey(final long k) {
        return longToKey(k, Long.MAX_VALUE - k);
    }

    public static Bytes longToKey(final long k1, final long k2) {
        final byte[] bytes = new byte[Long.BYTES * 2];
        ByteBuffer.wrap(bytes).putLong(k1).putLong(k2);
        return Bytes.wrap(bytes);
    }

    private ExampleLongLongKey() {}
}
