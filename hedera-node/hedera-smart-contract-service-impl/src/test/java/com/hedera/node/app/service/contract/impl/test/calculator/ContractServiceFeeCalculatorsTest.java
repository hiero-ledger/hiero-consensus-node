// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraDef;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeExtraIncluded;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeService;
import static org.hiero.hapi.fees.FeeScheduleUtils.makeServiceFee;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.contract.ContractGetBytecodeQuery;
import com.hedera.hapi.node.contract.ContractGetInfoQuery;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.service.contract.impl.calculator.ContractCallLocalFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractGetByteCodeFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractGetInfoFeeCalculator;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.spi.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.List;
import java.util.Set;
import org.hiero.hapi.support.fees.Extra;
import org.hiero.hapi.support.fees.FeeSchedule;
import org.hiero.hapi.support.fees.NetworkFee;
import org.hiero.hapi.support.fees.NodeFee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class ContractServiceFeeCalculatorsTest {
    @Mock
    private QueryContext queryContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(),
                Set.of(
                        new ContractCallLocalFeeCalculator(),
                        new ContractGetInfoFeeCalculator(),
                        new ContractGetByteCodeFeeCalculator()));
    }

    @Test
    void testContractCallLocal() {
        final var query = Query.newBuilder()
                .contractCallLocal(ContractCallLocalQuery.newBuilder())
                .build();
        final var result = feeCalculator.calculateQueryFee(query, queryContext);

        assertThat(result).isEqualTo(555);
    }

    @Test
    void testContractGetBytecode() {
        final var contractId = ContractID.newBuilder().contractNum(12333).build();
        final var contractStoreMock = mock(ContractStateStore.class);
        when(queryContext.createStore(ContractStateStore.class)).thenReturn(contractStoreMock);
        when(contractStoreMock.getBytecode(contractId))
                .thenReturn(Bytecode.newBuilder().code(Bytes.EMPTY).build());

        final var query = Query.newBuilder()
                .contractGetBytecode(ContractGetBytecodeQuery.newBuilder().contractID(contractId))
                .build();
        final var result = feeCalculator.calculateQueryFee(query, queryContext);

        assertThat(result).isEqualTo(666);
    }

    @Test
    void testContractGetInfo() {
        final var query = Query.newBuilder()
                .contractGetInfo(ContractGetInfoQuery.newBuilder())
                .build();
        final var result = feeCalculator.calculateQueryFee(query, queryContext);

        assertThat(result).isEqualTo(777);
    }

    private static FeeSchedule createTestFeeSchedule() {
        return FeeSchedule.DEFAULT
                .copyBuilder()
                .node(NodeFee.newBuilder()
                        .baseFee(100000L)
                        .extras(List.of(makeExtraIncluded(Extra.SIGNATURES, 1)))
                        .build())
                .network(NetworkFee.newBuilder().multiplier(2).build())
                .extras(
                        makeExtraDef(Extra.SIGNATURES, 1000000),
                        makeExtraDef(Extra.KEYS, 10000000),
                        makeExtraDef(Extra.BYTES, 10))
                .services(makeService(
                        "ContractService",
                        makeServiceFee(HederaFunctionality.CONTRACT_CALL_LOCAL, 555),
                        makeServiceFee(HederaFunctionality.CONTRACT_GET_BYTECODE, 666),
                        makeServiceFee(HederaFunctionality.CONTRACT_GET_INFO, 777)))
                .build();
    }
}
