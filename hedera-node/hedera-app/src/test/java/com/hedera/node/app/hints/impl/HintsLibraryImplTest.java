// SPDX-License-Identifier: Apache-2.0
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
