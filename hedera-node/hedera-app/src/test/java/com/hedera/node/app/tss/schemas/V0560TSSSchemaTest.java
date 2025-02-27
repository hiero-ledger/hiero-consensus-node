// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.tss.schemas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.state.lifecycle.StateDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class V0560TSSSchemaTest {
    private V0560TssBaseSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0560TssBaseSchema();
    }

    @Test
    void registersExpectedSchema() {
        final var statesToCreate = subject.statesToCreate();
        assertThat(statesToCreate.size()).isEqualTo(2);
        final var iter =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().iterator();
        assertEquals(V0560TssBaseSchema.TSS_MESSAGE_MAP_KEY, iter.next());
        assertEquals(V0560TssBaseSchema.TSS_VOTE_MAP_KEY, iter.next());
    }
}
