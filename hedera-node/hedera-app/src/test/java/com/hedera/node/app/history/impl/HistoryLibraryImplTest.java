// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.Test;

class HistoryLibraryImplTest {
    private static final HistoryLibraryImpl subject = new HistoryLibraryImpl();

    @Test
    void generatesValidSchnorrKeys() {
        final var keys = subject.newSchnorrKeyPair();
        final var message = Bytes.wrap("Hello, world!".getBytes());
        final var signature = subject.signSchnorr(message, Bytes.wrap(keys.signingKey()));
        assertTrue(subject.verifySchnorr(signature, message, Bytes.wrap(keys.verifyingKey())));
    }
}
