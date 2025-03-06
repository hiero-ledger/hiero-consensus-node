// SPDX-License-Identifier: Apache-2.0
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
