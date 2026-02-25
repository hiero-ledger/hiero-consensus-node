// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.NodeIdGeneratorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NodeIdGeneratorImplTest {

    @Mock
    private WritableEntityIdStore entityIdStore;

    private NodeIdGeneratorImpl subject;

    @BeforeEach
    void setup() {
        subject = new NodeIdGeneratorImpl(entityIdStore);
    }

    @Test
    void newNodeIdDelegatesToStore() {
        when(entityIdStore.incrementHighestNodeIdAndGet()).thenReturn(5L);

        final var actual = subject.newNodeId();

        assertThat(actual).isEqualTo(5L);
        verify(entityIdStore).incrementHighestNodeIdAndGet();
        verify(entityIdStore, never()).peekAtNextNodeId();
    }

    @Test
    void peekAtNewNodeIdDelegatesToStore() {
        when(entityIdStore.peekAtNextNodeId()).thenReturn(5L);

        final var actual = subject.peekAtNewNodeId();

        assertThat(actual).isEqualTo(5L);
        verify(entityIdStore).peekAtNextNodeId();
        verify(entityIdStore, never()).incrementHighestNodeIdAndGet();
    }

    @Test
    void newNodeIdReturnsIncrementingValues() {
        when(entityIdStore.incrementHighestNodeIdAndGet()).thenReturn(10L).thenReturn(11L);

        assertThat(subject.newNodeId()).isEqualTo(10L);
        assertThat(subject.newNodeId()).isEqualTo(11L);
    }
}
