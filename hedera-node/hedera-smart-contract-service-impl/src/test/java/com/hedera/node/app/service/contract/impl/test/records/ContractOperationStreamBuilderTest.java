// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.records;

import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
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
import com.hedera.hapi.streams.StorageChange;
import com.hedera.node.app.service.contract.impl.exec.CallOutcome;
import com.hedera.node.app.service.contract.impl.records.ContractOperationStreamBuilder;
import com.hedera.node.app.service.contract.impl.state.StorageAccess;
import com.hedera.node.app.service.contract.impl.state.StorageAccesses;
import com.hedera.node.app.service.contract.impl.state.TxStorageUsage;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import org.apache.tuweni.units.bigints.UInt256;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractOperationStreamBuilderTest {
    @Mock
    private HandleContext context;

    @Mock
    private ContractOperationStreamBuilder subject;

    @BeforeEach
    void setUp() {
        doCallRealMethod().when(subject).withCommonFieldsSetFrom(any(), eq(context));
    }

    @Test
    void setsAllCommonFieldsIfPresent() {
        final var stateChanges = new ContractStateChanges(List.of(new ContractStateChange(
                ContractID.DEFAULT,
                List.of(new StorageChange(
                        Bytes.fromHex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"),
                        Bytes.EMPTY,
                        Bytes.EMPTY)))));
        final var outcome = new CallOutcome(
                ContractFunctionResult.newBuilder().gasUsed(1L).build(),
                ResponseCodeEnum.SUCCESS,
                ContractID.DEFAULT,
                List.of(),
                null,
                null,
                null,
                EvmTransactionResult.newBuilder().gasUsed(1L).build(),
                null,
                null,
                new TxStorageUsage(
                        List.of(new StorageAccesses(
                                ContractID.DEFAULT,
                                List.of(new StorageAccess(UInt256.MAX_VALUE, UInt256.ZERO, UInt256.ZERO)))),
                        null));
        given(context.configuration()).willReturn(DEFAULT_CONFIG);
        final var builder = subject.withCommonFieldsSetFrom(outcome, context);

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
                null,
                null,
                null,
                EvmTransactionResult.newBuilder().gasUsed(1L).build(),
                null,
                null,
                null);
        final var builder = subject.withCommonFieldsSetFrom(outcome, context);

        verify(subject, never()).addContractActions(any(), anyBoolean());
        verify(subject, never()).addContractStateChanges(any(), anyBoolean());
        assertSame(subject, builder);
    }
}
