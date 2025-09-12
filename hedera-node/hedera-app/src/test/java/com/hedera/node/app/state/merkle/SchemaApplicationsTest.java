// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static com.hedera.node.app.state.merkle.SchemaApplicationType.MIGRATION;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.RESTART;
import static com.hedera.node.app.state.merkle.SchemaApplicationType.STATE_DEFINITIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.StateDefinition;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SchemaApplicationsTest {

    @SuppressWarnings("rawtypes")
    private static final StateDefinition STATE_DEFINITION =
            StateDefinition.singleton(123, "NUMBER", EntityNumber.PROTOBUF);

    private static final SemanticVersion LATEST_VERSION =
            SemanticVersion.newBuilder().major(3).build();
    private static final SemanticVersion PRECEDING_VERSION =
            SemanticVersion.newBuilder().major(2).build();
    private static final SemanticVersion FIRST_VERSION =
            SemanticVersion.newBuilder().major(1).build();

    @Mock
    private Schema schema;

    @Mock
    private Configuration config;

    private final SchemaApplications subject = new SchemaApplications();

    @Test
    void genesisUseWithNoCreatedOrRemovedStatesIsMigrateOnlyIfNotCurrent() {
        given(schema.getVersion()).willReturn(PRECEDING_VERSION);
        assertThat(subject.computeApplications(null, LATEST_VERSION, schema, config))
                .containsExactly(MIGRATION);
    }

    @Test
    void genesisUseWithCreatedStatesIsStateDefsAndMigrateIfNotCurrent() {
        given(schema.getVersion()).willReturn(PRECEDING_VERSION);
        given(schema.statesToCreate(config)).willReturn(Set.of(STATE_DEFINITION));
        assertThat(subject.computeApplications(null, LATEST_VERSION, schema, config))
                .containsExactly(STATE_DEFINITIONS, MIGRATION);
    }

    @Test
    void genesisUseWithRemovedStatesIsStateDefsAndMigrateIfNotCurrent() {
        given(schema.getVersion()).willReturn(PRECEDING_VERSION);
        given(schema.statesToRemove()).willReturn(Set.of(STATE_DEFINITION.stateId()));
        assertThat(subject.computeApplications(null, LATEST_VERSION, schema, config))
                .containsExactly(STATE_DEFINITIONS, MIGRATION);
    }

    @Test
    void genesisUseWithNoCreatedOrRemovedStatesIsMigrateAndRestartIfCurrent() {
        given(schema.getVersion()).willReturn(LATEST_VERSION);
        assertThat(subject.computeApplications(null, LATEST_VERSION, schema, config))
                .containsExactly(MIGRATION, RESTART);
    }

    @Test
    void restartUseWithEarlierStateDoesMigrate() {
        given(schema.getVersion()).willReturn(PRECEDING_VERSION);
        assertThat(subject.computeApplications(FIRST_VERSION, LATEST_VERSION, schema, config))
                .containsExactly(MIGRATION);
    }

    @Test
    void restartUseWithSameStateDoesNotMigrate() {
        given(schema.getVersion()).willReturn(PRECEDING_VERSION);
        assertThat(subject.computeApplications(PRECEDING_VERSION, LATEST_VERSION, schema, config))
                .isEmpty();
    }
}
