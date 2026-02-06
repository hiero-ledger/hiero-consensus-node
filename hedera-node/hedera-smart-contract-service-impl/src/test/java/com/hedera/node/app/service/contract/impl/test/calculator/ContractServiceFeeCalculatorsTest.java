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
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.contract.ContractCallLocalQuery;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractDeleteTransactionBody;
import com.hedera.hapi.node.contract.ContractGetBytecodeQuery;
import com.hedera.hapi.node.contract.ContractGetInfoQuery;
import com.hedera.hapi.node.contract.ContractUpdateTransactionBody;
import com.hedera.hapi.node.contract.EthereumTransactionBody;
import com.hedera.hapi.node.hooks.HookCreationDetails;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.fees.SimpleFeeCalculatorImpl;
import com.hedera.node.app.fees.SimpleFeeContextImpl;
import com.hedera.node.app.service.contract.impl.calculator.ContractCallFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractCallLocalFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractCreateFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractDeleteFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractGetByteCodeFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractGetInfoFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.ContractUpdateFeeCalculator;
import com.hedera.node.app.service.contract.impl.calculator.EthereumFeeCalculator;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.spi.fees.FeeContext;
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

    @Mock
    private FeeContext feeContext;

    private SimpleFeeCalculatorImpl feeCalculator;

    @BeforeEach
    void setUp() {
        var testSchedule = createTestFeeSchedule();
        feeCalculator = new SimpleFeeCalculatorImpl(
                testSchedule,
                Set.of(
                        new ContractCreateFeeCalculator(),
                        new ContractUpdateFeeCalculator(),
                        new ContractDeleteFeeCalculator(),
                        new ContractCallFeeCalculator(),
                        new EthereumFeeCalculator()),
                Set.of(
                        new ContractCallLocalFeeCalculator(),
                        new ContractGetInfoFeeCalculator(),
                        new ContractGetByteCodeFeeCalculator()));
    }

    @Test
    void testCreate() {
        final var body = TransactionBody.newBuilder()
                .contractCreateInstance(
                        ContractCreateTransactionBody.newBuilder().build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(1);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(499000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
    }

    @Test
    void testCreateWithAdminKey() {
        final var body = TransactionBody.newBuilder()
                .contractCreateInstance(ContractCreateTransactionBody.newBuilder()
                        .adminKey(Key.newBuilder()
                                .ed25519(Bytes.wrap(new byte[32]))
                                .build())
                        .build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(1);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(509000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
    }

    @Test
    void testCreateWithHook() {
        final var body = TransactionBody.newBuilder()
                .contractCreateInstance(ContractCreateTransactionBody.newBuilder()
                        .hookCreationDetails(List.of(HookCreationDetails.DEFAULT))
                        .build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(1);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(519000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
    }

    @Test
    void testUpdate() {
        final var body = TransactionBody.newBuilder()
                .contractUpdateInstance(
                        ContractUpdateTransactionBody.newBuilder().build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(3);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(2100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(499000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(4200000L);
    }

    @Test
    void testUpdateWithAdminKey() {
        final var body = TransactionBody.newBuilder()
                .contractUpdateInstance(ContractUpdateTransactionBody.newBuilder()
                        .adminKey(Key.newBuilder()
                                .ed25519(Bytes.wrap(new byte[32]))
                                .build())
                        .build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(3);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(2100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(509000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(4200000L);
    }

    @Test
    void testUpdateWithHook() {
        final var body = TransactionBody.newBuilder()
                .contractUpdateInstance(ContractUpdateTransactionBody.newBuilder()
                        .hookCreationDetails(List.of(HookCreationDetails.DEFAULT))
                        .hookIdsToDelete(List.of(1L))
                        .build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(3);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(2100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(539000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(4200000L);
    }

    @Test
    void testDelete() {
        final var body = TransactionBody.newBuilder()
                .contractDeleteInstance(
                        ContractDeleteTransactionBody.newBuilder().build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(2);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(1100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(69000000L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(2200000L);
    }

    @Test
    void testCall() {
        final var body = TransactionBody.newBuilder()
                .contractCall(ContractCallTransactionBody.newBuilder().build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(1);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(0L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(0L);
    }

    @Test
    void testEthereum() {
        final var body = TransactionBody.newBuilder()
                .ethereumTransaction(EthereumTransactionBody.newBuilder().build())
                .build();
        when(feeContext.numTxnSignatures()).thenReturn(1);

        final var result = feeCalculator.calculateTxFee(body, new SimpleFeeContextImpl(feeContext, null));

        assertThat(result.getNodeTotalTinycents()).isEqualTo(100000L);
        assertThat(result.getServiceTotalTinycents()).isEqualTo(0L);
        assertThat(result.getNetworkTotalTinycents()).isEqualTo(200000L);
    }

    @Test
    void testContractCallLocal() {
        final var query = Query.newBuilder()
                .contractCallLocal(ContractCallLocalQuery.newBuilder())
                .build();
        final var result = feeCalculator.calculateQueryFee(query, new SimpleFeeContextImpl(null, queryContext));

        assertThat(result.totalTinycents()).isEqualTo(555);
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
        final var result = feeCalculator.calculateQueryFee(query, new SimpleFeeContextImpl(null, queryContext));

        assertThat(result.totalTinycents()).isEqualTo(666);
    }

    @Test
    void testContractGetInfo() {
        final var query = Query.newBuilder()
                .contractGetInfo(ContractGetInfoQuery.newBuilder())
                .build();
        final var result = feeCalculator.calculateQueryFee(query, new SimpleFeeContextImpl(null, queryContext));

        assertThat(result.totalTinycents()).isEqualTo(777);
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
                        makeExtraDef(Extra.STATE_BYTES, 10),
                        makeExtraDef(Extra.HOOK_UPDATES, 20000000))
                .services(makeService(
                        "ContractService",
                        makeServiceFee(
                                HederaFunctionality.CONTRACT_CREATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 0),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000),
                                makeExtraIncluded(Extra.HOOK_UPDATES, 0)),
                        makeServiceFee(HederaFunctionality.CONTRACT_CALL, 0),
                        makeServiceFee(
                                HederaFunctionality.CONTRACT_UPDATE,
                                499000000,
                                makeExtraIncluded(Extra.KEYS, 0),
                                makeExtraIncluded(Extra.STATE_BYTES, 1000),
                                makeExtraIncluded(Extra.HOOK_UPDATES, 0)),
                        makeServiceFee(HederaFunctionality.CONTRACT_DELETE, 69000000),
                        makeServiceFee(HederaFunctionality.ETHEREUM_TRANSACTION, 0),
                        makeServiceFee(HederaFunctionality.CONTRACT_CALL_LOCAL, 555),
                        makeServiceFee(HederaFunctionality.CONTRACT_GET_BYTECODE, 666),
                        makeServiceFee(HederaFunctionality.CONTRACT_GET_INFO, 777)))
                .build();
    }
}
