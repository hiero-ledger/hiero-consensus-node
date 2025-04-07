// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.schemas;

import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.Key;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.addressbook.impl.test.handlers.AddressBookTestBase;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class V053AddressBookSchemaTest extends AddressBookTestBase {
    private static final Key NODE0_ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))
            .build();
    private static final Key NODE1_ADMIN_KEY = Key.newBuilder()
            .ed25519(Bytes.fromHex("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"))
            .build();

    private V053AddressBookSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V053AddressBookSchema();
    }

    @Test
    void registersExpectedSchema() {
        final var statesToCreate = subject.statesToCreate();
        assertThat(statesToCreate).hasSize(1);
        final var iter =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().iterator();
        assertEquals(NODES_KEY, iter.next());
    }

    @Test
    void parsesExpectedAdminKeys() {
        final Map<Long, Key> expectedKeys = Map.of(
                0L, NODE0_ADMIN_KEY,
                1L, NODE1_ADMIN_KEY);
        final var actualKeys = V053AddressBookSchema.parseEd25519NodeAdminKeys(nodeAdminKeysJson());
        assertEquals(expectedKeys, actualKeys);
    }

    private String nodeAdminKeysJson() {
        return """
                {
                  "0": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                }""";
    }
}
