// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static com.hedera.node.app.history.impl.HistoryLibraryImpl.RANDOM;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.hiero.base.utility.CommonUtils;
import org.junit.jupiter.api.Test;

class HistoryLibraryImplTest {
    private final HistoryLibraryImpl subject = new HistoryLibraryImpl();

    @Test
    void generatesValidSchnorrKeys() {
        final var keys = subject.newSchnorrKeyPair();
        final var message = Bytes.wrap("Hello, world!".getBytes());
        final var signature = subject.signSchnorr(message, Bytes.wrap(keys.signingKey()));
        assertTrue(subject.verifySchnorr(signature, message, Bytes.wrap(keys.verifyingKey())));
        System.out.println("RPM key lengths: " + keys.signingKey().length + ", " + keys.verifyingKey().length);

        final var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        final var wrapsKeys = HistoryLibraryImpl.WRAPS.generateSchnorrKeys(bytes);
        System.out.println("WRAPS key lengths: " + wrapsKeys.privateKey().length + ", " + wrapsKeys.publicKey().length);
        System.out.println("private: " + CommonUtils.hex(wrapsKeys.privateKey()));
        System.out.println("public: " + CommonUtils.hex(wrapsKeys.publicKey()));
        final var wrapsSignature = subject.signSchnorr(message, Bytes.wrap(wrapsKeys.privateKey()));
        assertTrue(subject.verifySchnorr(wrapsSignature, message, Bytes.wrap(wrapsKeys.publicKey())));
    }
}
