// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.state.merkle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.node.app.spi.fixtures.TestSchema;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.test.fixtures.MapWritableStates;
import com.swirlds.state.test.fixtures.StateTestBase;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SchemaTest extends StateTestBase {
    @Test
    @DisplayName("Schemas are sorted based on version number")
    void sorting() {
        // Given a list of schemas in ascending order
        final var schemaList = new ArrayList<Schema>();
        for (int maj = 0; maj < 3; maj++) {
            for (int min = 0; min < 3; min++) {
                for (int patch = 0; patch < 3; patch++) {
                    final var schema = new TestSchema(maj, min, patch);
                    schemaList.add(schema);
                }
            }
        }

        // When that list is shuffled and then sorted
        final var sortedList = new ArrayList<>(schemaList);
        Collections.shuffle(schemaList);
        Collections.sort(schemaList);

        // Then the elements are restored to ascending order
        assertThat(schemaList).containsExactlyElementsOf(sortedList);
    }

    @Test
    @DisplayName("A schema is equal to itself")
    void selfEquals() {
        final var schema = new TestSchema(1);
        assertThat(schema).isEqualTo(schema);
    }

    @Test
    @DisplayName("Two schemas are equal if their versions are equal")
    void equals() {
        final var schema1 = new TestSchema(1);
        final var schema2 = new TestSchema(1);
        assertThat(schema1).isEqualTo(schema2);
    }

    @Test
    @DisplayName("A schema is not equal to null")
    void notEqualToNull() {
        final var schema = new TestSchema(1);
        assertThat(schema).isNotEqualTo(null);
    }

    @Test
    @DisplayName("Two schemas of different versions are not equal")
    void unequal() {
        final var schema1 = new TestSchema(1);
        final var schema2 = new TestSchema(2);
        assertThat(schema1).isNotEqualTo(schema2);
    }

    @Test
    @DisplayName("Two equal schemas produce the same hashcode")
    @Disabled("Disabled until BasicSoftwareVersion implements hashCode")
    void hashCodeConsistentWithEquals() {
        final var schema1 = new TestSchema(1);
        final var schema2 = new TestSchema(1);
        assertThat(schema1).hasSameHashCodeAs(schema2);
    }

    @Test
    @DisplayName("Two different schemas probably produce different hash codes")
    void differentHashCodesConsistentWithNotEquals() {
        final var schema1 = new TestSchema(1);
        final var schema2 = new TestSchema(2);
        assertThat(schema1.hashCode()).isNotEqualTo(schema2.hashCode());
    }

    @Test
    @DisplayName("`getVersion` returns the version")
    void version() {
        final var schema1 = new TestSchema(1);
        assertThat(schema1.getVersion())
                .isEqualTo(SemanticVersion.newBuilder().major(1).build());
    }

    @Test
    @DisplayName("`statesToCreate` is empty when not overridden")
    void statesToCreate() {
        final var schema1 = new TestSchema(1);
        assertThat(schema1.statesToCreate()).isEmpty();
    }

    @Test
    @DisplayName("`statesToRemove` is empty when not overridden")
    void statesToRemove() {
        final var schema1 = new TestSchema(1);
        assertThat(schema1.statesToRemove()).isEmpty();
    }

    @Test
    @DisplayName("passing null context to migrate throws NPE")
    void nullNewStatesThrows() {
        final var schema1 = new TestSchema(1);
        final var prevStates = new MapWritableStates(Collections.emptyMap());
        //noinspection DataFlowIssue
        assertThatThrownBy(() -> schema1.migrate(null)).isInstanceOf(NullPointerException.class);
    }
}
