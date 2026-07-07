// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.CONTRACT_DELETED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_CONTRACT_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.node.app.service.contract.impl.utils.ConversionUtils.tuweniToPbjBytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.hyperledger.besu.evm.worldstate.CodeDelegationHelper.CODE_DELEGATION_PREFIX;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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
import com.hedera.node.app.service.contract.impl.handlers.ContractGetBytecodeHandler;
import com.hedera.node.app.service.contract.impl.state.ContractStateStore;
import com.hedera.node.app.service.contract.impl.state.ScheduleEvmAccount;
import com.hedera.node.app.service.contract.impl.state.TokenEvmAccount;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.schedule.ReadableScheduleStore;
import com.hedera.node.app.service.token.ReadableAccountStore;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.spi.fees.FeeCalculator;
import com.hedera.node.app.spi.fees.Fees;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Objects;
import org.bouncycastle.util.encoders.Hex;
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
    void findResponseFailsIfNoContractAccountTest() {
        givenNoContractAccount();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
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
    void findResponseIfTokenWasDeletedTest() {
        givenTokenWasDeleted();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        assertThat(Objects.requireNonNull(
                                subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                        .bytecode())
                .isEqualTo(Bytes.EMPTY);
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
        given(account.accountIdOrThrow()).willReturn(accountId);
        given(account.smartContract()).willReturn(true);

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

    @Test
    void returnsCodeDelegationIndicatorForEoaQueries() {
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);

        given(context.query()).willReturn(query);
        given(query.contractGetBytecodeOrThrow()).willReturn(contractGetBytecodeQuery);
        given(contractGetBytecodeQuery.contractIDOrElse(ContractID.DEFAULT)).willReturn(contractID);
        given(context.createStore(ReadableAccountStore.class)).willReturn(contractStore);
        given(contractStore.getContractById(contractID)).willReturn(account);
        given(account.smartContract()).willReturn(false);
        final var delegationAddressBytes =
                Bytes.wrap(Hex.decode("00000000000000000000000000000000000000000000000000000000cafebabe"));
        given(account.delegationAddress()).willReturn(delegationAddressBytes);

        final var response = subject.findResponse(context, responseHeader);

        assertThat(response.contractGetBytecodeResponse().header()).isEqualTo(responseHeader);
        assertThat(response.contractGetBytecodeResponse().bytecode())
                .isEqualTo(Bytes.merge(Bytes.wrap(CODE_DELEGATION_PREFIX.toArray()), delegationAddressBytes));
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
    void findResponseAccountIdAsContractId() {
        givenAccountIdAsContractId();
        given(account.accountIdOrThrow()).willReturn(accountId);
        given(account.smartContract()).willReturn(true);
        given(context.createStore(ContractStateStore.class)).willReturn(stateStore);
        final var expectedResult = Bytes.wrap(new byte[] {1, 2, 3, 4, 5});
        final var bytecode = Bytecode.newBuilder().code(expectedResult).build();
        given(stateStore.getBytecode(any())).willReturn(bytecode);

        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        Bytes resp = Objects.requireNonNull(
                        subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                .bytecode();
        assertThat(resp).isEqualTo(expectedResult);
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
    void findResponseTokenIdAsContractId() {
        givenTokenIdAsContractId();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        Bytes bytecode = Objects.requireNonNull(
                        subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                .bytecode();
        assertThat(bytecode).isEqualTo(tuweniToPbjBytes(TokenEvmAccount.CODE));
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
    void findResponseScheduleIdAsContractId() {
        givenScheduleIdAsContractId();
        given(entityIdFactory.newAccountId(contractID.contractNumOrElse(0L))).willReturn(accountId);
        given(responseHeader.nodeTransactionPrecheckCode()).willReturn(OK);
        given(responseHeader.responseType()).willReturn(ANSWER_ONLY);
        Bytes bytecode = Objects.requireNonNull(
                        subject.findResponse(context, responseHeader).contractGetBytecodeResponse())
                .bytecode();
        assertThat(bytecode).isEqualTo(tuweniToPbjBytes(ScheduleEvmAccount.CODE));
    }
}
