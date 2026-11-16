// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl.test.schemas;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MAX_QUEUE_DEPTH_HIGH_WATER_KEY;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.service.clpr.impl.ClprServiceImpl;
import com.hedera.node.app.service.clpr.impl.schemas.V0800ClprSchema;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class V0800ClprSchemaTest {
    @Test
    void registersLocalSingletonForUpgradeFromExistingClprSchemas() {
        final var registry = mock(SchemaRegistry.class);
        new ClprServiceImpl().registerSchemas(registry);
        final var schemas = ArgumentCaptor.forClass(Schema.class);
        verify(registry, org.mockito.Mockito.times(2)).register(schemas.capture());
        assertThat(schemas.getAllValues().get(1)).isInstanceOf(V0800ClprSchema.class);
        final var schema = new V0800ClprSchema();
        assertThat(schema.getVersion())
                .isEqualTo(SemanticVersion.newBuilder().minor(80).build());
        final var definition = schema.statesToCreate().iterator().next();
        assertThat(definition.stateId()).isEqualTo(MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID);
        assertThat(definition.stateKey()).isEqualTo(MAX_QUEUE_DEPTH_HIGH_WATER_KEY);
        assertThat(definition.valueCodec()).isEqualTo(ProtoLong.PROTOBUF);
        assertThat(definition.singleton()).isTrue();
    }
}
