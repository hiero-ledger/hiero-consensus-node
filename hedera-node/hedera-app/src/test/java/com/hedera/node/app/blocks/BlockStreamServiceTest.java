// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.blocks.schemas.V0560BlockStreamSchema;
import com.swirlds.state.lifecycle.SchemaRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
final class BlockStreamServiceTest {
    @Mock
    private SchemaRegistry schemaRegistry;

    private final BlockStreamService subject = new BlockStreamService();

    @Test
    void serviceNameAsExpected() {
        assertThat(subject.getServiceName()).isEqualTo("BlockStreamService");
    }

    @Test
    void enabledSubjectRegistersV0540Schema() {
        subject.registerSchemas(schemaRegistry);

        verify(schemaRegistry).register(argThat(s -> s instanceof V0560BlockStreamSchema));
    }
}
