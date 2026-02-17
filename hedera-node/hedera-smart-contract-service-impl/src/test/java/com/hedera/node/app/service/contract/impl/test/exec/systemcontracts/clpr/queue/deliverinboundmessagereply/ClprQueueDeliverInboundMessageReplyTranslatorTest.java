// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.queue.deliverinboundmessagereply;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.node.app.service.contract.impl.exec.systemcontracts.ClprQueueSystemContract.CLPR_QUEUE_CONTRACT_ID;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.DEFAULT_CONFIG;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.OWNER_BESU_ADDRESS;
import static com.hedera.node.app.service.contract.impl.test.TestHelpers.asHeadlongAddress;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Function;
import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessagereply.ClprQueueDeliverInboundMessageReplyTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessageresponse.ClprQueueEnqueueMessageResponseTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import org.apache.tuweni.bytes.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class ClprQueueDeliverInboundMessageReplyTranslatorTest extends CallAttemptTestBase {
    private static final int PACKED_OVERHEAD = 4 + Integer.BYTES;
    private static final TupleType<Tuple> ROUTE_HEADER_TYPE = TupleType.parse("(uint8,bytes32,address)");

    private static final Function HANDLE_MESSAGE_RESPONSE = new Function(
            "handleMessageResponse((uint64,(bytes),(bytes),(uint8,(uint256,string),(uint256,string),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes))))");

    private static final byte[] SOURCE_LEDGER_ID = new byte[] {
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44,
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44,
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44,
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44
    };

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractCallStreamBuilder callbackBuilder;

    private ClprQueueDeliverInboundMessageReplyTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new ClprQueueDeliverInboundMessageReplyTranslator(systemContractMethodRegistry, contractMetrics);
        lenient()
                .when(addressIdConverter.convertSender(OWNER_BESU_ADDRESS))
                .thenReturn(AccountID.newBuilder().accountNum(2L).build());
        lenient()
                .when(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .thenReturn(verificationStrategy);
    }

    @Test
    void selectorMatchesAdrLock() {
        assertThat(subject.identifyMethod(attemptWithInput(Bytes.wrap(
                        ClprQueueDeliverInboundMessageReplyTranslator.DELIVER_INBOUND_MESSAGE_REPLY_PACKED
                                .selector()))))
                .isPresent();
        assertThat(subject.identifyMethod(attemptWithInput(Bytes.fromHexString("8cfaaa60"))))
                .isEmpty();
    }

    @Test
    void malformedPackedInputReturnsTypedReason() {
        final var selectorOnly = Bytes.wrap(
                ClprQueueDeliverInboundMessageReplyTranslator.DELIVER_INBOUND_MESSAGE_REPLY_PACKED.selector());
        final var call = subject.callFrom(attemptWithInput(selectorOnly));
        final var result = call.execute(frame);

        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_BAD_CALLDATA");
    }

    @Test
    void unauthorizedSenderIsRejected() {
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS))
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var responseTuple = validResponseTuple(7L);
        final var input = packedReplyInput(validCanonicalResponseBytes(responseTuple));

        final var call = subject.callFrom(attemptWithInput(input));
        final var result = call.execute(frame);

        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_UNAUTHORIZED_CALLER");
        verify(systemContractOperations, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void responseDeliveryDispatchesMiddlewareCallback() {
        mockEntityIdFactory();
        final var responseTuple = validResponseTuple(7L);
        final var input = packedReplyInput(validCanonicalResponseBytes(responseTuple));

        given(systemContractOperations.dispatch(any(), any(), any(), any())).willReturn(callbackBuilder);
        given(callbackBuilder.status()).willReturn(SUCCESS);

        final var call = subject.callFrom(attemptWithInput(input));
        final var result = call.execute(frame);

        assertThat(result.fullResult().output()).isEqualTo(Bytes.EMPTY);

        final var bodyCaptor = ArgumentCaptor.forClass(TransactionBody.class);
        verify(systemContractOperations, times(1)).dispatch(bodyCaptor.capture(), any(), any(), any());
        final var callbackBody = bodyCaptor.getValue();
        assertThat(callbackBody.hasContractCall()).isTrue();

        final var expectedCallData = toArray(HANDLE_MESSAGE_RESPONSE.encodeCall(Tuple.singleton(responseTuple)));
        assertThat(callbackBody.contractCallOrThrow().functionParameters())
                .isEqualTo(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(expectedCallData));
    }

    private ClprQueueCallAttempt attemptWithInput(final Bytes input) {
        return new ClprQueueCallAttempt(
                input,
                new CallAttemptOptions<>(
                        CLPR_QUEUE_CONTRACT_ID,
                        OWNER_BESU_ADDRESS,
                        OWNER_BESU_ADDRESS,
                        false,
                        mockEnhancement(),
                        DEFAULT_CONFIG,
                        addressIdConverter,
                        verificationStrategies,
                        gasCalculator,
                        java.util.List.of(subject),
                        systemContractMethodRegistry,
                        false));
    }

    private static Tuple validResponseTuple(final long originalMessageId) {
        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var routeHeader =
                toArray(ROUTE_HEADER_TYPE.encode(Tuple.of(1, SOURCE_LEDGER_ID, asHeadlongAddress(OWNER_BESU_ADDRESS))));
        final var middlewareMessage = Tuple.of(balanceReport, routeHeader);
        final var middlewareResponse = Tuple.of(0, amount, amount, middlewareMessage);

        return Tuple.of(
                BigInteger.valueOf(originalMessageId),
                Tuple.singleton(new byte[] {0x11}),
                Tuple.singleton(new byte[] {0x22}),
                middlewareResponse);
    }

    private static byte[] validCanonicalResponseBytes(final Tuple responseTuple) {
        final var enqueueResponseCallData = toArray(
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.encodeCallWithArgs(responseTuple));
        return stripSelector(enqueueResponseCallData);
    }

    private static Bytes packedReplyInput(final byte[] canonicalResponseBytes) {
        final var selector =
                ClprQueueDeliverInboundMessageReplyTranslator.DELIVER_INBOUND_MESSAGE_REPLY_PACKED.selector();
        final var packed = ByteBuffer.allocate(PACKED_OVERHEAD + canonicalResponseBytes.length)
                .put(selector)
                .putInt(canonicalResponseBytes.length)
                .put(canonicalResponseBytes)
                .array();
        return Bytes.wrap(packed);
    }

    private static byte[] stripSelector(final byte[] callData) {
        final var out = new byte[callData.length - 4];
        System.arraycopy(callData, 4, out, 0, out.length);
        return out;
    }

    private static byte[] toArray(final ByteBuffer byteBuffer) {
        final var out = new byte[byteBuffer.remaining()];
        byteBuffer.get(out);
        return out;
    }
}
