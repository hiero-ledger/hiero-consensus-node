/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
