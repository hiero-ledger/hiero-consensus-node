// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.node.app.service.contract.impl.exec.StorageAccountingScope;
import com.hedera.node.app.spi.workflows.HandleContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StorageAccountingScopeTest {
    private static final ContractID FIRST =
            ContractID.newBuilder().contractNum(1001L).build();
    private static final ContractID SECOND =
            ContractID.newBuilder().contractNum(1002L).build();

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

    @Test
    void ownerWithoutNestedCreationsChecksAllItsCreations() {
        final var subject = new StorageAccountingScope(context);

        assertThat(subject.creationsToThrottle(List.of())).contains(List.of());
        assertThat(subject.creationsToThrottle(List.of(FIRST, SECOND))).contains(List.of(FIRST, SECOND));
    }

    @Test
    void throttlesEachCreationOnceInTheFirstCommitIncludingIt() {
        final var owner = new StorageAccountingScope(context);
        given(stack.findInLineage(StorageAccountingScope.class)).willReturn(owner);
        final var nested = new StorageAccountingScope(context);

        // A nested commit without creations needs no check
        assertThat(nested.creationsToThrottle(List.of())).isEmpty();
        assertThat(nested.creationsToThrottle(List.of(FIRST))).contains(List.of(FIRST));
        nested.creationsThrottled(List.of(FIRST));

        // The owner's commit includes the nested creation, but throttles only its own
        assertThat(owner.creationsToThrottle(List.of(FIRST))).isEmpty();
        assertThat(owner.creationsToThrottle(List.of(FIRST, SECOND))).contains(List.of(SECOND));
    }

    @Test
    void onlyRecordedCreationsCountAsThrottled() {
        final var owner = new StorageAccountingScope(context);
        given(stack.findInLineage(StorageAccountingScope.class)).willReturn(owner);
        final var nested = new StorageAccountingScope(context);

        // The nested commit is denied, so it records nothing
        assertThat(nested.creationsToThrottle(List.of(FIRST))).contains(List.of(FIRST));

        assertThat(owner.creationsToThrottle(List.of())).contains(List.of());
    }
}
