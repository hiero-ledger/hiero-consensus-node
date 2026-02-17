// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.clpr.queue.enqueuemessageresponse;

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
import static org.mockito.Mockito.verify;

import com.esaulpaugh.headlong.abi.Tuple;
import com.esaulpaugh.headlong.abi.TupleType;
import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.contract.impl.exec.metrics.ContractMetrics;
import com.hedera.node.app.service.contract.impl.exec.scope.VerificationStrategy;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.ClprQueueCallAttempt;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.clpr.queue.enqueuemessageresponse.ClprQueueEnqueueMessageResponseTranslator;
import com.hedera.node.app.service.contract.impl.exec.systemcontracts.common.CallAttemptOptions;
import com.hedera.node.app.service.contract.impl.records.ContractCallStreamBuilder;
import com.hedera.node.app.service.contract.impl.test.exec.systemcontracts.common.CallAttemptTestBase;
import java.math.BigInteger;
import org.apache.tuweni.bytes.Bytes;
import org.hiero.hapi.interledger.state.clpr.ClprLedgerId;
import org.hiero.hapi.interledger.state.clpr.ClprMessageQueueMetadata;
import org.hiero.interledger.clpr.ReadableClprMessageQueueMetadataStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

class ClprQueueEnqueueMessageResponseTranslatorTest extends CallAttemptTestBase {
    private static final TupleType<Tuple> ROUTE_HEADER_TYPE = TupleType.parse("(uint8,bytes32,address)");
    private static final TupleType<Tuple> BAD_ROUTE_HEADER_TYPE = TupleType.parse("(uint8,address)");
    private static final byte[] REMOTE_LEDGER_ID = new byte[] {
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11,
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11,
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11,
        0x55, 0x66, 0x77, 0x11, 0x55, 0x66, 0x77, 0x11
    };
    private static final com.hedera.pbj.runtime.io.buffer.Bytes ZERO_HASH =
            com.hedera.pbj.runtime.io.buffer.Bytes.wrap(new byte[48]);
    private static final AccountID SENDER_ID =
            AccountID.newBuilder().accountNum(1234L).build();

    @Mock
    private ContractMetrics contractMetrics;

    @Mock
    private ReadableClprMessageQueueMetadataStore queueStore;

    @Mock
    private VerificationStrategy verificationStrategy;

    @Mock
    private ContractCallStreamBuilder recordBuilder;

    private ClprQueueEnqueueMessageResponseTranslator subject;

    @BeforeEach
    void setUp() {
        subject = new ClprQueueEnqueueMessageResponseTranslator(systemContractMethodRegistry, contractMetrics);
        lenient().when(nativeOperations.readableClprMessageQueueMetadataStore()).thenReturn(queueStore);
        lenient().when(addressIdConverter.convertSender(OWNER_BESU_ADDRESS)).thenReturn(SENDER_ID);
        lenient()
                .when(verificationStrategies.activatingOnlyContractKeysFor(any(), anyBoolean(), any()))
                .thenReturn(verificationStrategy);
        lenient()
                .when(systemContractOperations.dispatch(any(), any(), any(), any()))
                .thenReturn(recordBuilder);
        lenient().when(recordBuilder.status()).thenReturn(com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS);
    }

    @Test
    void selectorMatchesAdrLock() {
        assertThat(subject.identifyMethod(attemptWithInput(Bytes.fromHexString("b26aa82b"))))
                .isPresent();
        assertThat(subject.identifyMethod(attemptWithInput(Bytes.fromHexString("8cfaaa60"))))
                .isEmpty();
    }

    @Test
    void malformedCalldataReturnsTypedReason() {
        final var call = subject.callFrom(attemptWithInput(Bytes.fromHexString("b26aa82b")));
        final var result = call.execute(frame);
        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_BAD_CALLDATA");
    }

    @Test
    void responsePathEnqueuesReplyAndReturnsAssignedId() {
        final long originalMessageId = 7L;
        given(queueStore.get(any())).willReturn(initialQueueMetadata(REMOTE_LEDGER_ID));

        final var input = validResponseInput((byte) 1, REMOTE_LEDGER_ID, originalMessageId);
        final var call = subject.callFrom(attemptWithInput(input));
        final var result = call.execute(frame);
        final var decodedOutput = ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE
                .getOutputs()
                .decode(result.fullResult().output().toArrayUnsafe());

        assertThat(decodedOutput.get(0) instanceof BigInteger).isTrue();
        assertThat(((BigInteger) decodedOutput.get(0)).longValue()).isEqualTo(1L);

        final var bodyCaptor = ArgumentCaptor.forClass(TransactionBody.class);
        verify(systemContractOperations).dispatch(bodyCaptor.capture(), any(), any(), any());
        final var synthBody = bodyCaptor.getValue();
        final var op = synthBody.clprEnqueueMessageOrThrow();
        assertThat(op.ledgerIdOrThrow().ledgerId())
                .isEqualTo(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(REMOTE_LEDGER_ID));
        assertThat(op.expectedMessageId()).isEqualTo(1L);

        assertThat(op.payloadOrThrow().messageReplyOrThrow().messageId()).isEqualTo(originalMessageId);
        final var expectedCanonicalBytes = expectedCanonicalBytes(input.toArrayUnsafe());
        assertThat(op.payloadOrThrow().messageReplyOrThrow().messageReplyData())
                .isEqualTo(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(expectedCanonicalBytes));
    }

    @Test
    void unsupportedRouteVersionReturnsTypedReason() {
        final var call = subject.callFrom(attemptWithInput(validResponseInput((byte) 2, REMOTE_LEDGER_ID, 1L)));
        final var result = call.execute(frame);
        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_UNSUPPORTED_ROUTE_VERSION");
    }

    @Test
    void invalidOriginalMessageIdReturnsTypedReason() {
        final var call = subject.callFrom(attemptWithInput(validResponseInput((byte) 1, REMOTE_LEDGER_ID, 0L)));
        final var result = call.execute(frame);
        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_INVALID_ORIGINAL_MESSAGE_ID");
    }

    @Test
    void zeroRouteLedgerIdReturnsTypedReason() {
        final var call = subject.callFrom(attemptWithInput(validResponseInput((byte) 1, new byte[32], 1L)));
        final var result = call.execute(frame);
        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_INVALID_REMOTE_LEDGER_ID");
    }

    @Test
    void malformedRouteHeaderBytesReturnsTypedReason() {
        final var call =
                subject.callFrom(attemptWithInput(validResponseInputWithRouteHeader(new byte[] {0x01, 0x02}, 1L)));
        final var result = call.execute(frame);
        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_BAD_ROUTE_ENVELOPE");
    }

    @Test
    void wrongRouteHeaderTupleShapeReturnsTypedReason() {
        final var badRouteHeaderBuffer = BAD_ROUTE_HEADER_TYPE.encode(
                Tuple.of(Byte.toUnsignedInt((byte) 1), asHeadlongAddress(OWNER_BESU_ADDRESS)));
        final var badRouteHeaderBytes = new byte[badRouteHeaderBuffer.remaining()];
        badRouteHeaderBuffer.get(badRouteHeaderBytes);

        final var call = subject.callFrom(attemptWithInput(validResponseInputWithRouteHeader(badRouteHeaderBytes, 1L)));
        final var result = call.execute(frame);
        assertThat(new String(result.fullResult().output().toArrayUnsafe(), UTF_8))
                .isEqualTo("CLPR_QUEUE_BAD_ROUTE_ENVELOPE");
    }

    private ClprMessageQueueMetadata initialQueueMetadata(final byte[] remoteLedgerId) {
        final var remoteLedger = ClprLedgerId.newBuilder()
                .ledgerId(com.hedera.pbj.runtime.io.buffer.Bytes.wrap(remoteLedgerId))
                .build();
        return ClprMessageQueueMetadata.newBuilder()
                .ledgerId(remoteLedger)
                .nextMessageId(1L)
                .sentMessageId(0L)
                .receivedMessageId(0L)
                .sentRunningHash(ZERO_HASH)
                .receivedRunningHash(ZERO_HASH)
                .build();
    }

    private Bytes validResponseInput(
            final byte routeVersion, final byte[] routeLedgerId, final long originalMessageId) {
        final var routeHeaderBuffer = ROUTE_HEADER_TYPE.encode(
                Tuple.of(Byte.toUnsignedInt(routeVersion), routeLedgerId, asHeadlongAddress(OWNER_BESU_ADDRESS)));
        final var routeHeaderBytes = new byte[routeHeaderBuffer.remaining()];
        routeHeaderBuffer.get(routeHeaderBytes);
        return validResponseInputWithRouteHeader(routeHeaderBytes, originalMessageId);
    }

    private Bytes validResponseInputWithRouteHeader(final byte[] routeHeaderBytes, final long originalMessageId) {
        final var amount = Tuple.of(BigInteger.ONE, "tinybar");
        final var balanceReport = Tuple.of(new byte[32], amount, amount, amount);
        final var middlewareMessage = Tuple.of(balanceReport, routeHeaderBytes);
        final var middlewareResponse = Tuple.of(0, amount, amount, middlewareMessage);
        final var response = Tuple.of(
                BigInteger.valueOf(originalMessageId),
                Tuple.singleton(new byte[] {0x11}),
                Tuple.singleton(new byte[] {0x22}),
                middlewareResponse);

        return Bytes.wrapByteBuffer(
                ClprQueueEnqueueMessageResponseTranslator.ENQUEUE_MESSAGE_RESPONSE.encodeCallWithArgs(response));
    }

    private static byte[] expectedCanonicalBytes(final byte[] callData) {
        final var out = new byte[callData.length - 4];
        System.arraycopy(callData, 4, out, 0, out.length);
        return out;
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
}
