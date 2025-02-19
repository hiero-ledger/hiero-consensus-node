/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
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

package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class HintsLibraryCodecTest {
    private final HintsLibraryCodec subject = new HintsLibraryCodec();

    @Test
    void nothingSupportedYet() {
        assertThrows(UnsupportedOperationException.class, () -> subject.decodeCrsUpdate(Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.encodeHintsKey(Bytes.EMPTY, Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.extractAggregationKey(Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.extractVerificationKey(Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.extractPublicKey(Bytes.EMPTY, 0));
        assertThrows(UnsupportedOperationException.class, () -> subject.extractWeight(Bytes.EMPTY, 0));
        assertThrows(UnsupportedOperationException.class, () -> subject.extractTotalWeight(Bytes.EMPTY));
        assertThrows(UnsupportedOperationException.class, () -> subject.decodePreprocessedKeys(Bytes.EMPTY));
    }
}
