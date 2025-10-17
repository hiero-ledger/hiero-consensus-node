// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.token.IndirectKeyUsersKey;
import com.hedera.hapi.node.state.token.IndirectKeyUsersValue;
import com.hedera.node.app.service.token.impl.schemas.V0620TokenSchema;
import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V0620TokenSchemaTest {

    private final V0620TokenSchema subject = new V0620TokenSchema();

    @Test
    @DisplayName("verify states to create")
    void verifyStatesToCreate() {
        var sortedResult = subject.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();

        final var def = sortedResult.getFirst();
        assertThat(def.stateKey()).isEqualTo(V0620TokenSchema.INDIRECT_KEY_USERS_KEY);
        assertThat(def.keyCodec()).isEqualTo(IndirectKeyUsersKey.PROTOBUF);
        assertThat(def.valueCodec()).isEqualTo(IndirectKeyUsersValue.PROTOBUF);
    }
}
