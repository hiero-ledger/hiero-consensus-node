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
import java.util.Map;
import org.junit.jupiter.api.Test;

class HintsLibraryImplTest {
    private final HintsLibraryImpl subject = new HintsLibraryImpl();

    @Test
    void nothingSupportedYet() {
        assertThrows(UnsupportedOperationException.class, () -> subject.newCrs(1));
        assertThrows(UnsupportedOperationException.class, () -> subject.updateCrs(Bytes.EMPTY, Bytes.EMPTY));
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.verifyCrsUpdate(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY));

        assertThrows(UnsupportedOperationException.class, subject::newBlsKeyPair);
        assertThrows(UnsupportedOperationException.class, () -> subject.computeHints(Bytes.EMPTY, 1, 1));
        assertThrows(UnsupportedOperationException.class, () -> subject.validateHintsKey(Bytes.EMPTY, 1, 1));
        assertThrows(UnsupportedOperationException.class, () -> subject.preprocess(Map.of(), Map.of(), 1));
        assertThrows(UnsupportedOperationException.class, () -> subject.signBls(Bytes.EMPTY, Bytes.EMPTY));
        assertThrows(
                UnsupportedOperationException.class, () -> subject.verifyBls(Bytes.EMPTY, Bytes.EMPTY, Bytes.EMPTY));
        assertThrows(
                UnsupportedOperationException.class,
                () -> subject.aggregateSignatures(Bytes.EMPTY, Bytes.EMPTY, Map.of()));
    }
}
