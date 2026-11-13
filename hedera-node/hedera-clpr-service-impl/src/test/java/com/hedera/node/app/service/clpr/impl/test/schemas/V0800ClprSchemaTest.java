// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.schemas;

import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_ENTRIES_KEY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_ENTRIES_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_KEY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema.CHANNEL_INDEX_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.clpr.ClprChannelIndex;
import com.hedera.hapi.node.state.primitives.ProtoBytes;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema;
import com.swirlds.state.lifecycle.StateDefinition;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class V0800ClprSchemaTest {
    private final V0800ClprSchema subject = new V0800ClprSchema();

    @Test
    void hasVersionMatchingItsName() {
        assertThat(subject.getVersion())
                .isEqualTo(
                        SemanticVersion.newBuilder().major(0).minor(80).patch(0).build());
    }

    @Test
    void createsTheChannelIndexSingletonAndEntries() {
        final var definitions = subject.statesToCreate().stream()
                .collect(Collectors.toMap(StateDefinition::stateId, Function.identity()));
        assertThat(definitions).containsOnlyKeys(CHANNEL_INDEX_STATE_ID, CHANNEL_INDEX_ENTRIES_STATE_ID);

        final var index = definitions.get(CHANNEL_INDEX_STATE_ID);
        assertThat(index.stateKey()).isEqualTo(CHANNEL_INDEX_KEY);
        assertThat(index.singleton()).isTrue();
        assertThat(index.valueCodec()).isEqualTo(ClprChannelIndex.PROTOBUF);

        final var entries = definitions.get(CHANNEL_INDEX_ENTRIES_STATE_ID);
        assertThat(entries.stateKey()).isEqualTo(CHANNEL_INDEX_ENTRIES_KEY);
        assertThat(entries.keyValue()).isTrue();
        assertThat(entries.keyCodec()).isEqualTo(ProtoLong.PROTOBUF);
        assertThat(entries.valueCodec()).isEqualTo(ProtoBytes.PROTOBUF);
    }
}
