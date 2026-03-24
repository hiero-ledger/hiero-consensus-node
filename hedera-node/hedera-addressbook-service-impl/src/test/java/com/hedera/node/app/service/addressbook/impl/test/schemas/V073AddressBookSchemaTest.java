// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.test.schemas;

import static com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema.REGISTERED_NODES_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.service.addressbook.impl.schemas.V073AddressBookSchema;
import com.swirlds.state.lifecycle.StateDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class V073AddressBookSchemaTest {

    private V073AddressBookSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V073AddressBookSchema();
    }

    @Test
    void registersExpectedSchema() {
        final var statesToCreate = subject.statesToCreate();
        assertThat(statesToCreate).hasSize(1);
        final var iter =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().iterator();
        assertEquals(REGISTERED_NODES_KEY, iter.next());
    }

    @Test
    void hasCorrectVersion() {
        final var expected =
                SemanticVersion.newBuilder().major(0).minor(73).patch(0).build();
        assertEquals(expected, subject.getVersion());
    }
}
