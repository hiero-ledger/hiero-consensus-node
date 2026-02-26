// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.NOT_SUPPORTED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessage.ClprQueueDeliverInboundMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessagereply.ClprQueueDeliverInboundMessageReplyTranslator;
import com.hedera.node.app.service.contract.impl.handlers.ClprMessagePayloadHandler;
import com.hedera.node.app.spi.workflows.DispatchOptions;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import java.util.Arrays;
import org.hiero.hapi.interledger.clpr.ClprHandleMessagePayloadTransactionBody;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessage;
import org.hiero.hapi.interledger.state.clpr.ClprMessagePayload;
import org.hiero.hapi.interledger.state.clpr.ClprMessageReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClprMessagePayloadHandlerTest {
    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    @Mock
    private StreamBuilder streamBuilder;

    private ClprMessagePayloadHandler subject;

    @BeforeEach
    void setUp() {
        subject = new ClprMessagePayloadHandler();
    }

    @Test
    void preHandleRejectsUserSubmittedTransactions() {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.clprEnabled", "true")
                .getOrCreateConfig();
        given(preHandleContext.configuration()).willReturn(config);
        given(preHandleContext.isUserTransaction()).willReturn(true);

        assertThatThrownBy(() -> subject.preHandle(preHandleContext))
                .isInstanceOf(PreCheckException.class)
                .hasFieldOrPropertyWithValue("responseCode", NOT_SUPPORTED);
    }

    @Test
    void handleDispatchesRequestPayloadWithRequestSelector() throws HandleException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.clprEnabled", "true")
                .withValue("contracts.maxGasPerTransaction", 2_000_000L)
                .getOrCreateConfig();

        final var op = ClprHandleMessagePayloadTransactionBody.newBuilder()
                .sourceLedgerId(ledgerId())
                .inboundMessageId(7L)
                .payload(ClprMessagePayload.newBuilder()
                        .message(ClprMessage.newBuilder()
                                .messageData(Bytes.wrap("request"))
                                .build())
                        .build())
                .build();

        final var txBody =
                TransactionBody.newBuilder().clprHandleMessagePayload(op).build();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.body()).willReturn(txBody);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(2L).build());
        given(handleContext.dispatch(any())).willReturn(streamBuilder);
        given(streamBuilder.status()).willReturn(SUCCESS);

        subject.handle(handleContext);

        final var optionsCaptor =
                ArgumentCaptor.forClass((Class<DispatchOptions<StreamBuilder>>) (Class<?>) DispatchOptions.class);
        verify(handleContext).dispatch(optionsCaptor.capture());
        final var dispatchedBody = optionsCaptor.getValue().body();
        final var callData =
                dispatchedBody.contractCallOrThrow().functionParameters().toByteArray();

        assertThat(dispatchedBody.contractCallOrThrow().contractIDOrThrow().contractNum())
                .isEqualTo(0x16eL);
        assertThat(Arrays.copyOfRange(callData, 0, 4))
                .isEqualTo(ClprQueueDeliverInboundMessageTranslator.DELIVER_INBOUND_MESSAGE_PACKED.selector());
    }

    @Test
    void handleDispatchesReplyPayloadWithReplySelector() throws HandleException {
        final var config = HederaTestConfigBuilder.create()
                .withValue("clpr.clprEnabled", "true")
                .withValue("contracts.maxGasPerTransaction", 2_000_000L)
                .getOrCreateConfig();

        final var op = ClprHandleMessagePayloadTransactionBody.newBuilder()
                .sourceLedgerId(ledgerId())
                .inboundMessageId(8L)
                .payload(ClprMessagePayload.newBuilder()
                        .messageReply(ClprMessageReply.newBuilder()
                                .messageId(8L)
                                .messageReplyData(Bytes.wrap("reply"))
                                .build())
                        .build())
                .build();

        final var txBody =
                TransactionBody.newBuilder().clprHandleMessagePayload(op).build();
        given(handleContext.configuration()).willReturn(config);
        given(handleContext.body()).willReturn(txBody);
        given(handleContext.payer())
                .willReturn(AccountID.newBuilder().accountNum(2L).build());
        given(handleContext.dispatch(any())).willReturn(streamBuilder);
        given(streamBuilder.status()).willReturn(SUCCESS);

        subject.handle(handleContext);

        final var optionsCaptor =
                ArgumentCaptor.forClass((Class<DispatchOptions<StreamBuilder>>) (Class<?>) DispatchOptions.class);
        verify(handleContext).dispatch(optionsCaptor.capture());
        final var callData = optionsCaptor
                .getValue()
                .body()
                .contractCallOrThrow()
                .functionParameters()
                .toByteArray();

        assertThat(Arrays.copyOfRange(callData, 0, 4))
                .isEqualTo(
                        ClprQueueDeliverInboundMessageReplyTranslator.DELIVER_INBOUND_MESSAGE_REPLY_PACKED.selector());
    }

    private static ClprLedgerId ledgerId() {
        final var bytes = new byte[32];
        bytes[31] = 0x01;
        return ClprLedgerId.newBuilder().ledgerId(Bytes.wrap(bytes)).build();
    }
}
