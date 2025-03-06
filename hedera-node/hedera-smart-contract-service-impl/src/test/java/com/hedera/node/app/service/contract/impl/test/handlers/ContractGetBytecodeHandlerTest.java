// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.ContractID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.contract.ContractGetBytecodeQuery;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetBytecodeHandler;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.swirlds.state.lifecycle.EntityIdFactory;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractGetBytecodeHandlerTest {
    @Mock
    private QueryContext context;

    @Mock
    private ContractGetBytecodeQuery contractGetBytecodeQuery;

    @Mock
    private FeeCalculator feeCalculator;

    @Mock
    private QueryHeader header;

    @Mock
    private ResponseHeader responseHeader;

    @Mock
    private Query query;

    @Mock
    private ContractID contractID;

    @Mock
    private ReadableAccountStore store;

    @Mock
    private ContractStateStore contractStore;

    @Mock
    private Account contract;

    @Mock
    private AccountID accountID;

    @Mock
    private EntityIdFactory entityIdFactory;

    private ContractGetBytecodeHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ContractGetBytecodeHandler(entityIdFactory);
    }

    @Test
    void extractHeaderTest() {
        // given:
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.header()).willReturn(header);

        // when:
        var header = subject.extractHeader(query);

        // then:
        assertThat(header).isNotNull();
    }

    @Test
    void createEmptyResponseTest() {
        // when:
        var response = subject.createEmptyResponse(responseHeader);

        // then:
        assertThat(response).isNotNull();
    }

    @Test
    void validatePositiveTest() {
        // given
        given(context.createStore(ReadableAccountStore.class)).willReturn(store);
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(store.getContractById(contractID)).willReturn(contract);
        given(contract.smartContract()).willReturn(true);

        // when:
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validateFailsIfNoContractIdTest() {
        // given
        given(context.createStore(ReadableAccountStore.class)).willReturn(store);
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(null);

        // when:
        assertThatThrownBy(() -> subject.validate(context)).isInstanceOf(PreCheckException.class);
    }

    @Test
    void computeFeesWithNullContractTest() {
        // given
        when(context.feeCalculator()).thenReturn(feeCalculator);
        when(context.query()).thenReturn(query);
        when(query.contractGetBytecodeOrThrow()).thenReturn(contractGetBytecodeQuery);

        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        when(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).thenReturn(defaultHeader);

        when(context.createStore(ReadableAccountStore.class)).thenReturn(store);
        when(store.getContractById(any())).thenReturn(null);

        final var components = FeeComponents.newBuilder()
                .setMax(15000)
                .setBpt(25)
                .setVpt(25)
                .setRbh(25)
                .setGas(25)
                .build();
        final var nodeData = com.hederahashgraph.api.proto.java.FeeData.newBuilder()
                .setNodedata(components)
                .build();

        when(feeCalculator.legacyCalculate(any())).thenAnswer(invocation -> {
            Function<SigValueObj, com.hederahashgraph.api.proto.java.FeeData> function = invocation.getArgument(0);
            final var feeData = function.apply(new SigValueObj(1, 1, 1));
            long nodeFee = FeeBuilder.getComponentFeeInTinyCents(nodeData.getNodedata(), feeData.getNodedata());
            return new Fees(nodeFee, 0L, 0L);
        });

        // when
        Fees actualFees = subject.computeFees(context);

        // then
        assertThat(actualFees.nodeFee()).isEqualTo(5L);
        assertThat(actualFees.networkFee()).isZero();
        assertThat(actualFees.serviceFee()).isZero();
    }

    @Test
    void findResponsePositiveTest() {
        // given
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);

        given(context.createStore(ReadableAccountStore.class)).willReturn(store);
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(store.getContractById(contractID)).willReturn(contract);
        given(contract.smartContract()).willReturn(true);

        given(context.createStore(ContractStateStore.class)).willReturn(contractStore);
        given(contract.accountIdOrThrow()).willReturn(accountID);
        final var expectedResult = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var bytecode = Bytecode.newBuilder().code(expectedResult).build();
        given(contractStore.getBytecode(any())).willReturn(bytecode);

        // when:
        var response = subject.findResponse(context, responseHeader);

        assertThat(response.contractGetBytecodeResponse().header()).isEqualTo(responseHeader);
        assertThat(response.contractGetBytecodeResponse().bytecode()).isEqualTo(expectedResult);
    }
}
