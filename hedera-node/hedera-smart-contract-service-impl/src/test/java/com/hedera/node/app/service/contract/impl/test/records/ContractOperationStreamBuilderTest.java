// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.records;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.contract.EvmTransactionResult;
import com.hedera.hapi.streams.ContractActions;
import com.hedera.hapi.streams.ContractStateChange;
import com.hedera.hapi.streams.ContractStateChanges;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractOperationStreamBuilderTest {
    @Mock
    private ContractOperationStreamBuilder subject;

    @BeforeEach
    void setUp() {
        doCallRealMethod().when(subject).withCommonFieldsSetFrom(any());
    }

    @Test
    void setsAllCommonFieldsIfPresent() {
        final var stateChanges = new ContractStateChanges(List.of(ContractStateChange.DEFAULT));
        final var outcome = new CallOutcome(
                ContractFunctionResult.newBuilder().gasUsed(1L).build(),
                ResponseCodeEnum.SUCCESS,
                ContractID.DEFAULT,
                List.of(),
                stateChanges,
                null,
                null,
                null,
                null,
                EvmTransactionResult.newBuilder().gasUsed(1L).build(),
                null,
                null);
        final var builder = subject.withCommonFieldsSetFrom(outcome);

        verify(subject).addContractActions(ContractActions.DEFAULT, false);
        verify(subject).addContractStateChanges(stateChanges, false);
        assertSame(subject, builder);
    }

    @Test
    void skipsCommonFieldsIfNotPresent() {
        final var outcome = new CallOutcome(
                ContractFunctionResult.newBuilder().gasUsed(1L).build(),
                ResponseCodeEnum.SUCCESS,
                ContractID.DEFAULT,
                null,
                ContractStateChanges.DEFAULT,
                null,
                null,
                null,
                null,
                EvmTransactionResult.newBuilder().gasUsed(1L).build(),
                null,
                null);
        final var builder = subject.withCommonFieldsSetFrom(outcome);

        verify(subject, never()).addContractActions(any(), anyBoolean());
        verify(subject, never()).addContractStateChanges(any(), anyBoolean());
        assertSame(subject, builder);
    }
}
