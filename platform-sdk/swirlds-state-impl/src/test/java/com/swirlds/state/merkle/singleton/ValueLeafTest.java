// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.merkle.singleton;

import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.swirlds.state.test.fixtures.merkle.MerkleTestBase;
import org.junit.jupiter.api.Test;

@Deprecated
class ValueLeafTest extends MerkleTestBase {
    @Test
    void setValue() {
        setupSingletonCountry();
        final var leaf = new ValueLeaf<>(singletonClassId(COUNTRY_STATE_KEY), ProtoBytes.PROTOBUF, DENMARK);
        assertThat(leaf.getValue()).isEqualTo(DENMARK);
        leaf.setValue(FRANCE);
        assertThat(leaf.getValue()).isEqualTo(FRANCE);
    }
}
