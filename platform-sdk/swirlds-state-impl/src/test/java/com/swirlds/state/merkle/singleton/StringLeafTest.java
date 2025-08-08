// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import com.swirlds.state.test.fixtures.merkle.singleton.StringLeaf;
import org.junit.jupiter.api.Test;

@Deprecated
class StringLeafTest extends MerkleTestBase {
    @Test
    void setValue() {
        final var leaf = new StringLeaf("Label 1");
        assertThat(leaf.getLabel()).isEqualTo("Label 1");
        leaf.setLabel("Label 2");
        assertThat(leaf.getLabel()).isEqualTo("Label 2");
    }

    @Test
    void labelTooLong() {
        final var label = randomString(2000);
        assertThatThrownBy(() -> new StringLeaf(label))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum length");
    }
}
