// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.service.contract.impl.exec.StorageAccountingScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageAccountingScopeTest {
    @Mock
    private HandleContext context;

    @Mock
    private HandleContext.SavepointStack stack;

    @Mock
    private StorageAccountingScope enclosingScope;

    @BeforeEach
    void setUp() {
        given(context.savepointStack()).willReturn(stack);
    }

    @Test
    void ownsScopeWithoutOneInItsLineage() {
        final var subject = new StorageAccountingScope(context);

        assertThat(subject.isOwner()).isTrue();
        subject.open();
        subject.close();

        final var inOrder = inOrder(stack);
        inOrder.verify(stack).attach(StorageAccountingScope.class, subject);
        inOrder.verify(stack).detach(StorageAccountingScope.class);
    }

    @Test
    void joinsScopeInItsLineage() {
        given(stack.findInLineage(StorageAccountingScope.class)).willReturn(enclosingScope);

        final var subject = new StorageAccountingScope(context);

        assertThat(subject.isOwner()).isFalse();
        subject.open();
        subject.close();

        verify(stack, never()).attach(any(), any());
        verify(stack, never()).detach(any());
    }
}
