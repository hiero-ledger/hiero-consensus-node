// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
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
import com.hedera.hapi.node.base.ScheduleID;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.contract.ContractGetBytecodeQuery;
import com.hedera.hapi.node.state.contract.Bytecode;
import com.hedera.hapi.node.state.schedule.Schedule;
import com.hedera.hapi.node.state.token.Account;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.node.app.hapi.utils.fee.FeeBuilder;
import com.hedera.node.app.hapi.utils.fee.SigValueObj;
import com.hedera.node.app.service.contract.impl.handlers.ContractGetBytecodeHandler;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.utils.RedirectBytecodeUtils;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.swirlds.state.lifecycle.EntityIdFactory;
import java.util.Objects;
import java.util.function.Function;
import org.hyperledger.besu.datatypes.Address;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContractGetBytecodeHandlerTest {

    @Mock(strictness = Strictness.LENIENT)
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
    private ReadableAccountStore contractStore;

    @Mock
    private ContractID contractID;

    @Mock
    private AccountID accountId;

    @Mock
    private Account account;

    @Mock
    private ReadableTokenStore tokenStore;

    @Mock
    private TokenID tokenId;

    @Mock
    private Token token;

    @Mock
    private ReadableScheduleStore scheduleStore;

    @Mock
    private ScheduleID scheduleID;

    @Mock
    private Schedule schedule;

    @Mock
    private ContractStateStore stateStore;

    @Mock
    private EntityIdFactory entityIdFactory;

    private ContractGetBytecodeHandler subject;

    @Mock
    private Fees fee;

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
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(contractStore.getContractById(contractID)).willReturn(account);

        // when:
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    private void givenNoContractId() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(null);
    }

    @Test
    void validateFailsIfNoContractIdTest() {
        givenNoContractId();
        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .hasMessage(INVALID_CONTRACT_ID.protoName());
    }

    @Test
    void computeFeesIfNoContractIdTest() {
        givenNoContractId();
        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        given(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).willReturn(defaultHeader);
        given(context.feeCalculator()).willReturn(feeCalculator);
        given(feeCalculator.legacyCalculate(any())).willReturn(fee);
        assertThat(subject.computeFees(context)).isEqualTo(fee);
    }

    @Test
    void findResponseIfNoContractIdTest() {
        givenNoContractId();
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        assertThat(Objects.requireNonNull(
                                subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                        .bytecode())
                .isEqualTo(Bytes.EMPTY);
    }

    private void givenNoContractAccount() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(contractStore.getContractById(contractID)).willReturn(null);
        given(context.createStore(ReadableTokenStore.class)).willReturn(tokenStore);
        given(entityIdFactory.newTokenId(contractID.contractNumOrElse(0L))).willReturn(tokenId);
        given(tokenStore.get(tokenId)).willReturn(null);
        given(context.createStore(ReadableScheduleStore.class)).willReturn(scheduleStore);
        given(entityIdFactory.newScheduleId(contractID.contractNumOrElse(0L))).willReturn(scheduleID);
        given(scheduleStore.get(scheduleID)).willReturn(null);
    }

    @Test
    void validateIfNoContractAccountTest() {
        givenNoContractAccount();
        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .hasMessage(INVALID_CONTRACT_ID.protoName());
    }

    @Test
    void computeFeesIfNoContractAccountTest() {
        givenNoContractAccount();
        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        given(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).willReturn(defaultHeader);
        given(context.feeCalculator()).willReturn(feeCalculator);
        given(feeCalculator.legacyCalculate(any())).willReturn(fee);
        assertThat(subject.computeFees(context)).isEqualTo(fee);
    }

    @Test
    void findResponseFailsIfNoContractAccountTest() {
        givenNoContractAccount();
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        assertThat(Objects.requireNonNull(
                                subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                        .bytecode())
                .isEqualTo(Bytes.EMPTY);
    }

    private void givenContractWasDeleted() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(contractStore.getContractById(contractID)).willReturn(account);
        given(account.deleted()).willReturn(true);
    }

    @Test
    void validateFailsIfContractWasDeletedTest() {
        givenContractWasDeleted();
        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .hasMessage(CONTRACT_DELETED.protoName());
    }

    @Test
    void computeFeesIfContractWasDeletedTest() {
        givenContractWasDeleted();
        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        given(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).willReturn(defaultHeader);
        given(context.feeCalculator()).willReturn(feeCalculator);
        given(feeCalculator.legacyCalculate(any())).willReturn(fee);
        assertThat(subject.computeFees(context)).isEqualTo(fee);
    }

    @Test
    void findResponseIfContractWasDeletedTest() {
        givenContractWasDeleted();
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        assertThat(Objects.requireNonNull(
                                subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                        .bytecode())
                .isEqualTo(Bytes.EMPTY);
    }

    private void givenTokenWasDeleted() {
        // given
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(contractStore.getContractById(contractID)).willReturn(null);
        given(context.createStore(ReadableTokenStore.class)).willReturn(tokenStore);
        given(entityIdFactory.newTokenId(contractID.contractNumOrElse(0L))).willReturn(tokenId);
        given(tokenStore.get(tokenId)).willReturn(token);
        given(token.deleted()).willReturn(true);
    }

    @Test
    void validateFailsIfTokenWasDeletedTest() {
        givenTokenWasDeleted();
        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .hasMessage(CONTRACT_DELETED.protoName());
    }

    @Test
    void computeFeesIfTokenWasDeletedTest() {
        givenTokenWasDeleted();
        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        given(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).willReturn(defaultHeader);
        given(context.feeCalculator()).willReturn(feeCalculator);
        given(feeCalculator.legacyCalculate(any())).willReturn(fee);
        assertThat(subject.computeFees(context)).isEqualTo(fee);
    }

    @Test
    void findResponseIfTokenWasDeletedTest() {
        givenTokenWasDeleted();
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        assertThat(Objects.requireNonNull(
                                subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                        .bytecode())
                .isEqualTo(Bytes.EMPTY);
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

        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(contractStore.getContractById(contractID)).willReturn(account);
        given(account.smartContract()).willReturn(true);
        given(account.accountIdOrThrow()).willReturn(accountId);

        given(context.createStore(ContractStateStore.class)).willReturn(stateStore);

        final var expectedResult = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var bytecode = Bytecode.newBuilder().code(expectedResult).build();
        given(stateStore.getBytecode(any())).willReturn(bytecode);

        // when:
        var response = subject.findResponse(context, responseHeader);

        assertThat(Objects.requireNonNull(response.contractGetBytecodeResponse())
                        .header())
                .isEqualTo(responseHeader);
        assertThat(Objects.requireNonNull(response.contractGetBytecodeResponse())
                        .bytecode())
                .isEqualTo(expectedResult);
    }

    private void givenAccountIdAsContractId() {
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        given(contractStore.getAccountById(accountId)).willReturn(account);
    }

    @Test
    void validateAccountIdAsContractId() {
        givenAccountIdAsContractId();
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void computeFeesAccountIdAsContractId() {
        givenAccountIdAsContractId();
        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        given(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).willReturn(defaultHeader);
        given(context.feeCalculator()).willReturn(feeCalculator);
        given(feeCalculator.legacyCalculate(any())).willReturn(fee);
        assertThat(subject.computeFees(context)).isEqualTo(fee);
    }

    @Test
    void findResponseAccountIdAsContractId() {
        givenAccountIdAsContractId();
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        Bytes bytecode = Objects.requireNonNull(
                        subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                .bytecode();
        assertThat(bytecode).isEqualTo(RedirectBytecodeUtils.accountProxyBytecodePjb(Address.ZERO));
    }

    private void givenTokenIdAsContractId() {
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(context.createStore(ReadableTokenStore.class)).willReturn(tokenStore);
        given(entityIdFactory.newTokenId(contractID.contractNumOrElse(0L))).willReturn(tokenId);
        given(tokenStore.get(tokenId)).willReturn(token);
    }

    @Test
    void validateTokenIdAsContractId() {
        givenTokenIdAsContractId();
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void computeFeesTokenIdAsContractId() {
        givenTokenIdAsContractId();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        given(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).willReturn(defaultHeader);
        given(context.feeCalculator()).willReturn(feeCalculator);
        given(feeCalculator.legacyCalculate(any())).willReturn(fee);
        assertThat(subject.computeFees(context)).isEqualTo(fee);
    }

    @Test
    void findResponseTokenIdAsContractId() {
        givenTokenIdAsContractId();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        Bytes bytecode = Objects.requireNonNull(
                        subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                .bytecode();
        assertThat(bytecode).isEqualTo(RedirectBytecodeUtils.tokenProxyBytecodePjb(Address.ZERO));
    }

    private void givenScheduleIdAsContractId() {
        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(context.createStore(ReadableTokenStore.class)).willReturn(tokenStore);
        given(entityIdFactory.newTokenId(contractID.contractNumOrElse(0L))).willReturn(tokenId);
        given(context.createStore(ReadableScheduleStore.class)).willReturn(scheduleStore);
        given(entityIdFactory.newScheduleId(contractID.contractNumOrElse(0L))).willReturn(scheduleID);
        given(scheduleStore.get(scheduleID)).willReturn(schedule);
    }

    @Test
    void validateScheduleIdAsContractId() {
        givenScheduleIdAsContractId();
        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void computeFeesScheduleIdAsContractId() {
        givenScheduleIdAsContractId();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        QueryHeader defaultHeader =
                QueryHeader.newBuilder().responseType(ANSWER_ONLY).build();
        given(contractGetBytecodeQuery.headerOrElse(QueryHeader.DEFAULT)).willReturn(defaultHeader);
        given(context.feeCalculator()).willReturn(feeCalculator);
        given(feeCalculator.legacyCalculate(any())).willReturn(fee);
        assertThat(subject.computeFees(context)).isEqualTo(fee);
    }

    @Test
    void findResponseScheduleIdAsContractId() {
        givenScheduleIdAsContractId();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        Bytes bytecode = Objects.requireNonNull(
                        subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                .bytecode();
        assertThat(bytecode).isEqualTo(RedirectBytecodeUtils.scheduleProxyBytecodePjb(Address.ZERO));
    }
}
