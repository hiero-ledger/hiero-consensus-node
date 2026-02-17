// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.queue.deliverinboundmessage;

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
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.deliverinboundmessage.ClprQueueDeliverInboundMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessage.ClprQueueEnqueueMessageTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessageresponse.ClprQueueEnqueueMessageResponseTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ReadableClprMessageQueueMetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class ClprQueueDeliverInboundMessageTranslatorTest extends CallAttemptTestBase {
    private static final int PACKED_OVERHEAD = 4 + 32 + Long.BYTES + Integer.BYTES;
    private static final TupleType<Tuple> ROUTE_HEADER_TYPE = TupleType.parse("(uint8,bytes32,address,address)");
    private static final TupleType<Tuple> RESPONSE_ROUTE_HEADER_TYPE = TupleType.parse("(uint8,bytes32,address)");

    private static final Function HANDLE_MESSAGE = new Function(
            "handleMessage((address,(address,bytes32,(uint256,string),bytes),bytes32,(bool,(uint256,string),bytes),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes)),uint64)",
            "((uint64,(bytes),(bytes),(uint8,(uint256,string),(uint256,string),((bytes32,(uint256,string),(uint256,string),(uint256,string)),bytes))))");

    private static final byte[] SOURCE_LEDGER_ID = new byte[] {
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44,
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44,
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44,
        0x11, 0x22, 0x33, 0x44, 0x11, 0x22, 0x33, 0x44
    };

    private static final byte[] REMOTE_LEDGER_ID = new byte[] {
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11,
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11,
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11,
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11
    };

    private static final com.hedera.pbj.runtime.io.buffer.Bytes ZERO_HASH =
            com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[48]);

    private static final AccountID SUPERUSER_ID =
            AccountID.newBuilder().accountNum(2L).build();

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private ReadableClprMessageQueueMetadataStore queueStore;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractCallStreamBuilder callbackBuilder;

    @Mock
    private ContractCallStreamBuilder enqueueBuilder;

    private ClprQueueDeliverInboundMessageTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new ClprQueueDeliverInboundMessageTranslator(systemContractMethodRegistry, contractMetrics);
        lenient().when(nativeOperations.readableClprMessageQueueMetadataStore()).thenReturn(queueStore);
        lenient().when(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).thenReturn(SUPERUSER_ID);
        lenient()
                .when(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .thenReturn(verificationStrategy);
    }

    @Test
    void selectorMatchesAdrLock() {
        assertThat(subject.identifyMethod(attemptWithInput(Bytes.wrap(
                        ClprQueueDeliverInboundMessageTranslator.DELIVER_INBOUND_MESSAGE_PACKED.selector()))))
                .isPresent();
        assertThat(subject.identifyMethod(attemptWithInput(Bytes.fromHexString("8cfaaa60"))))
                .isEmpty();
    }

    @Test
    void malformedPackedInputReturnsTypedReason() {
        final var selectorOnly =
                Bytes.wrap(ClprQueueDeliverInboundMessageTranslator.DELIVER_INBOUND_MESSAGE_PACKED.selector());
        final var call = subject.callFrom(attemptWithInput(selectorOnly));
        final var result = call.execute(frame);

        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_BAD_CALLDATA");
    }

    @Test
    void unauthorizedSenderIsRejected() {
        given(addressIdConverter.convertSender(OWNER_BESU_ADDRESS))
                .willReturn(AccountID.newBuilder().accountNum(1234L).build());

        final var input = packedRequestInput(validCanonicalMessageBytes(validEnqueueMessageTuple()), 7L);
        final var call = subject.callFrom(attemptWithInput(input));
        final var result = call.execute(frame);

        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_UNAUTHORIZED_CALLER");
        verify(systemContractOperations, never()).dispatch(any(), any(), any(), any());
    }

    @Test
    void requestDeliveryDispatchesMiddlewareAndEnqueuesReply() {
        mockEntityIdFactory();
        final long inboundMessageId = 7L;
        given(queueStore.get(any())).willReturn(initialQueueMetadata(SOURCE_LEDGER_ID, 11L));
        given(systemContractOperations.dispatch(any(), any(), any(), any()))
                .willReturn(callbackBuilder, enqueueBuilder);
        given(callbackBuilder.status()).willReturn(SUCCESS);
        given(callbackBuilder.hasContractResult()).willReturn(true);
        given(callbackBuilder.getEvmCallResult())
                .willReturn(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(validHandleMessageReturn(inboundMessageId)));
        given(enqueueBuilder.status()).willReturn(SUCCESS);

        final var messageTuple = validEnqueueMessageTuple();
        final var canonicalMessageBytes = validCanonicalMessageBytes(messageTuple);
        final var input = packedRequestInput(canonicalMessageBytes, inboundMessageId);

        final var call = subject.callFrom(attemptWithInput(input));
        final var result = call.execute(frame);

        final var decodedOutput = ClprQueueDeliverInboundMessageTranslator.DELIVER_INBOUND_MESSAGE_PACKED
                .getOutputs()
                .decode(result.fullResult().output().toArrayUnsafe());
        assertThat(((BigInteger) decodedOutput.get(0)).longValue()).isEqualTo(11L);

        final var bodyCaptor = ArgumentCaptor.forClass(TransactionBody.class);
        verify(systemContractOperations, times(2)).dispatch(bodyCaptor.capture(), any(), any(), any());
        final var dispatchBodies = bodyCaptor.getAllValues();

        final var callbackBody = dispatchBodies.get(0);
        assertThat(callbackBody.hasContractCall()).isTrue();
        final var expectedHandleMessageCallData =
                toArray(HANDLE_MESSAGE.encodeCall(Tuple.of(messageTuple, BigInteger.valueOf(inboundMessageId))));
        assertThat(callbackBody.contractCallOrThrow().functionParameters())
                .isEqualTo(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(expectedHandleMessageCallData));

        final var enqueueBody = dispatchBodies.get(1);
        assertThat(enqueueBody.hasClprEnqueueMessage()).isTrue();
        final var enqueueOp = enqueueBody.clprEnqueueMessageOrThrow();
        assertThat(enqueueOp.expectedMessageId()).isEqualTo(11L);
        assertThat(enqueueOp.ledgerIdOrThrow().ledgerId())
                .isEqualTo(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(SOURCE_LEDGER_ID));
        assertThat(enqueueOp.payloadOrThrow().messageReplyOrThrow().messageId()).isEqualTo(inboundMessageId);

        final var responseTuple = decodeCanonicalResponseTuple(enqueueOp
                .payloadOrThrow()
                .messageReplyOrThrow()
                .messageReplyData()
                .toByteArray());
        final var middlewareResponse = (Tuple) responseTuple.get(3);
        final var middlewareMessage = (Tuple) middlewareResponse.get(3);
        final var routeHeaderBytes = (byte[]) middlewareMessage.get(1);
        final var routeHeader = RESPONSE_ROUTE_HEADER_TYPE.decode(routeHeaderBytes);

        assertThat(((Number) routeHeader.get(0)).intValue()).isEqualTo(1);
        assertThat((byte[]) routeHeader.get(1)).containsExactly(SOURCE_LEDGER_ID);
        assertThat((com.esaulpaugh.headlong.abi.Address) routeHeader.get(2))
                .isEqualTo(asHeadlongAddress(OWNER_BESU_ADDRESS));
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

    private static ClprMessageQueueMetadata initialQueueMetadata(
            final byte[] remoteLedgerId, final long nextMessageId) {
        final var remoteLedger = ClprLedgerId.newBuilder()
                .ledgerId(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(remoteLedgerId))
                .build();
        return ClprMessageQueueMetadata.newBuilder()
                .ledgerId(remoteLedger)
                .nextMessageId(nextMessageId)
                .sentMessageId(0L)
                .receivedMessageId(0L)
                .sentRunningHash(ZERO_HASH)
                .receivedRunningHash(ZERO_HASH)
                .build();
    }

    private static Tuple validEnqueueMessageTuple() {
        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var applicationMessage =
                Tuple.of(asHeadlongAddress(OWNER_BESU_ADDRESS), new byte[32], amount, new byte[] {0x01});
        final var connectorMessage = Tuple.of(true, amount, new byte[] {0x02});
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);

        final var routeHeader = toArray(ROUTE_HEADER_TYPE.encode(Tuple.of(
                1, REMOTE_LEDGER_ID, asHeadlongAddress(OWNER_BESU_ADDRESS), asHeadlongAddress(OWNER_BESU_ADDRESS))));
        final var middlewareMessage = Tuple.of(balanceReport, routeHeader);

        return Tuple.of(
                asHeadlongAddress(OWNER_BESU_ADDRESS),
                applicationMessage,
                new byte[32],
                connectorMessage,
                middlewareMessage);
    }

    private static byte[] validCanonicalMessageBytes(final Tuple enqueueMessageTuple) {
        final var enqueueCallData =
                toArray(ClprQueueEnqueueMessageTranslator.ENQUEUE_MESSAGE.encodeCallWithArgs(enqueueMessageTuple));
        return stripSelector(enqueueCallData);
    }

    private static byte[] validHandleMessageReturn(final long inboundMessageId) {
        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, new byte[] {0x01});
        final var middlewareResponse = Tuple.of(0, amount, amount, middlewareMessage);
        final var responseTuple = Tuple.of(
                BigInteger.valueOf(inboundMessageId),
                Tuple.singleton(new byte[] {0x11}),
                Tuple.singleton(new byte[] {0x22}),
                middlewareResponse);
        return toArray(HANDLE_MESSAGE.getOutputs().encode(Tuple.singleton(responseTuple)));
    }

    private static Bytes packedRequestInput(final byte[] canonicalMessageBytes, final long inboundMessageId) {
        final var selector = ClprQueueDeliverInboundMessageTranslator.DELIVER_INBOUND_MESSAGE_PACKED.selector();
        final var packed = ByteBuffer.allocate(PACKED_OVERHEAD + canonicalMessageBytes.length)
                .put(selector)
                .put(SOURCE_LEDGER_ID)
                .putLong(inboundMessageId)
                .putInt(canonicalMessageBytes.length)
                .put(canonicalMessageBytes)
                .array();
        return Bytes.wrap(packed);
    }

    private static Tuple decodeCanonicalResponseTuple(final byte[] canonicalResponseBytes) {
        final var enqueueResponseCallData = prependSelector(
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.selector(), canonicalResponseBytes);
        final var enqueueResponseArgs =
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.decodeCall(enqueueResponseCallData);
        return (Tuple) enqueueResponseArgs.get(0);
    }

    private static byte[] stripSelector(final byte[] callData) {
        final var out = new byte[callData.length - 4];
        System.arraycopy(callData, 4, out, 0, out.length);
        return out;
    }

    private static byte[] prependSelector(final byte[] selector, final byte[] arguments) {
        final var out = new byte[selector.length + arguments.length];
        System.arraycopy(selector, 0, out, 0, selector.length);
        System.arraycopy(arguments, 0, out, selector.length, arguments.length);
        return out;
    }

    private static byte[] toArray(final ByteBuffer byteBuffer) {
        final var out = new byte[byteBuffer.remaining()];
        byteBuffer.get(out);
        return out;
    }
}
