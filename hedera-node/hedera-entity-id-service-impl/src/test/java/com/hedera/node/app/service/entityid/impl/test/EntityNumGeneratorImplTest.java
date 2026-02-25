// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.entityid.impl.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.service.entityid.WritableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.EntityNumGeneratorImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EntityNumGeneratorImplTest {

    @Mock
    private WritableEntityIdStore entityIdStore;

    private EntityNumGeneratorImpl subject;

    @BeforeEach
    void setup() {
        subject = new EntityNumGeneratorImpl(entityIdStore);
    }

    @Test
    void testNewEntityNumWithInitialState() {
        when(entityIdStore.incrementAndGet()).thenReturn(1L);
        final var actual = subject.newEntityNum();

        assertThat(actual).isEqualTo(1L);
        verify(entityIdStore).incrementAndGet();
    }

    @Test
    void testPeekingAtNewEntityNumWithInitialState() {
        when(entityIdStore.peekAtNextNumber()).thenReturn(1L);
        final var actual = subject.peekAtNewEntityNum();

        assertThat(actual).isEqualTo(1L);

        verify(entityIdStore).peekAtNextNumber();
    }

    @Test
    void testNewEntityNum() {
        when(entityIdStore.incrementAndGet()).thenReturn(43L);

        final var actual = subject.newEntityNum();

        assertThat(actual).isEqualTo(43L);
        verify(entityIdStore).incrementAndGet();
        verify(entityIdStore, never()).peekAtNextNumber();
    }

    @Test
    void testPeekingAtNewEntityNum() {
        when(entityIdStore.peekAtNextNumber()).thenReturn(43L);

        final var actual = subject.peekAtNewEntityNum();

        assertThat(actual).isEqualTo(43L);
        verify(entityIdStore).peekAtNextNumber();
        verify(entityIdStore, never()).incrementAndGet();
    }
}
