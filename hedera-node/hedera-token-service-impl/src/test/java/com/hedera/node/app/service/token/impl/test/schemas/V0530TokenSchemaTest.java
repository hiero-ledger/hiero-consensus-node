// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.schemas;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.PendingAirdropId;
import com.hedera.hapi.node.state.token.AccountPendingAirdrop;
import com.hedera.node.app.service.token.impl.schemas.V0530TokenSchema;
import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Comparator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V0530TokenSchemaTest {

    private final V0530TokenSchema subject = new V0530TokenSchema();

    @Test
    @DisplayName("verify states to create")
    void verifyStatesToCreate() {
        var sortedResult = subject.statesToCreate().stream()
                .sorted(Comparator.comparing(StateDefinition::stateKey))
                .toList();

        final var firstStateDef = sortedResult.getFirst();
        assertThat(firstStateDef.stateKey()).isEqualTo("PENDING_AIRDROPS");
        assertThat(firstStateDef.keyCodec()).isEqualTo(PendingAirdropId.PROTOBUF);
        assertThat(firstStateDef.valueCodec()).isEqualTo(AccountPendingAirdrop.PROTOBUF);
    }
}
