// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import org.bouncycastle.util.encoders.Hex;
import org.hiero.base.crypto.Hash;

public class HashDeserializer extends StdDeserializer<Hash> {

    public HashDeserializer() {
        this(null);
    }

    public HashDeserializer(final Class<?> vc) {
        super(vc);
    }

    @Override
    public Hash deserialize(final JsonParser p, final DeserializationContext ctxt) throws IOException {
        return new Hash(Hex.decode(p.getValueAsString()));
    }
}
