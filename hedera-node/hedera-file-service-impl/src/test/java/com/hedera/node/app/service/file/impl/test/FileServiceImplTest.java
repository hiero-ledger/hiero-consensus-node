// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.file.impl.test;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import com.swirlds.state.lifecycle.StateDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileServiceImplTest {

    @Mock
    private SchemaRegistry registry;

    public static final Configuration DEFAULT_CONFIG = HederaTestConfigBuilder.createConfig();

    @Test
    void registersExpectedSchema() {
        ArgumentCaptor<Schema> schemaCaptor = ArgumentCaptor.forClass(Schema.class);

        subject().registerSchemas(registry);

        verify(registry).register(schemaCaptor.capture());

        final var schema = schemaCaptor.getValue();

        final var statesToCreate = schema.statesToCreate(DEFAULT_CONFIG);
        assertThat(11).isEqualTo(statesToCreate.size());
        final var iter =
                statesToCreate.stream().map(StateDefinition::stateId).sorted().iterator();
        assertThat(V0490FileSchema.FILES_STATE_ID).isEqualTo(iter.next());
    }

    private FileService subject() {
        return new FileServiceImpl();
    }
}
