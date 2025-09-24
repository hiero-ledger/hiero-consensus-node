// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.consensus.impl.test.schemas;

import static com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema.TOPICS_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hedera.node.app.service.consensus.impl.schemas.V0490ConsensusSchema;
import com.swirlds.state.lifecycle.StateDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the initial mod-service schema for the consensus service.
 */
@ExtendWith(MockitoExtension.class)
public class V0490ConsensusSchemaTest {
    private V0490ConsensusSchema subject;

    @BeforeEach
    void setUp() {
        subject = new V0490ConsensusSchema();
    }

    @Test
    void registersExpectedSchema() {
        final var statesToCreate = subject.statesToCreate();
        assertThat(statesToCreate.size()).isEqualTo(1);
        final var iter =
                statesToCreate.stream().map(StateDefinition::stateKey).sorted().iterator();
        assertEquals(TOPICS_KEY, iter.next());
    }
}
