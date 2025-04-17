// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.io.InputOutputStream;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.hiero.base.constructable.ConstructableRegistry;
import org.hiero.base.constructable.ConstructableRegistryException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SignatureTest {
    @BeforeAll
    public static void setUp() throws ConstructableRegistryException {
        ConstructableRegistry.getInstance().registerConstructables("com.swirlds.common.crypto");
    }

    @Test
    public void serializeDeserializeTest() throws IOException {
        SignatureType signatureType = SignatureType.RSA;
        byte[] sigBytes = new byte[signatureType.signatureLength()];
        ThreadLocalRandom.current().nextBytes(sigBytes);
        Signature signature = new Signature(signatureType, sigBytes);
        Signature deserialized = serializeDeserialize(signature);
        assertEquals(signature, deserialized);
    }

    private Signature serializeDeserialize(final Signature signature) throws IOException {
        try (final InputOutputStream io = new InputOutputStream()) {
            signature.serialize(io.getOutput(), true);
            io.startReading();
            return Signature.deserialize(io.getInput(), true);
        }
    }
}
